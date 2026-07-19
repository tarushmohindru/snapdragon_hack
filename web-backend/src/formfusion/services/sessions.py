import hashlib
import hmac
import secrets
import string
import uuid
from datetime import UTC, datetime, timedelta

from formfusion.config import Settings
from formfusion.contracts.http import (
    CloseSessionResponse,
    CreateSessionResponse,
    JoinSessionResponse,
    SessionStatusResponse,
    SessionSummaryResponse,
)
from formfusion.domain.errors import InvalidJoinCode, SessionExpired, SessionFull
from formfusion.domain.models import SessionRecord
from formfusion.repositories.sqlite import SqliteRepository


class SessionService:
    def __init__(self, repository: SqliteRepository, settings: Settings) -> None:
        self.repository = repository
        self._settings = settings

    def _digest_join_code(self, session_id: str, join_code: str) -> str:
        return hmac.new(
            self._settings.join_code_secret.encode(),
            f"{session_id}:{join_code}".encode(),
            hashlib.sha256,
        ).hexdigest()

    @staticmethod
    def _status(session: SessionRecord) -> SessionStatusResponse:
        return SessionStatusResponse(
            session_id=session.session_id,
            exercise=session.exercise,
            device_ids=sorted(session.devices),
            calibrated=session.calibrated,
            calibration_reprojection_error=session.calibration_reprojection_error,
            latest_result_at=session.latest_result_at,
            started_at=session.created_at,
            ended_at=session.ended_at,
            expires_at=session.expires_at,
        )

    async def create(self, exercise: str) -> CreateSessionResponse:
        session_id = str(uuid.uuid4())
        alphabet = string.ascii_uppercase + string.digits
        join_code = "".join(secrets.choice(alphabet) for _ in range(8))
        now = datetime.now(UTC)
        expires_at = now + timedelta(seconds=self._settings.session_ttl_seconds)
        await self.repository.create(
            SessionRecord(
                session_id=session_id,
                join_code_digest=self._digest_join_code(session_id, join_code),
                exercise=exercise,
                created_at=now,
                expires_at=expires_at,
            )
        )
        return CreateSessionResponse(
            session_id=session_id,
            join_code=join_code,
            expires_at=expires_at,
        )

    async def get_record(self, session_id: str, allow_closed: bool = True) -> SessionRecord:
        session = await self.repository.get(session_id)
        if session.expired:
            raise SessionExpired(f"session {session_id} has expired")
        if not allow_closed and session.ended_at is not None:
            raise SessionExpired(f"session {session_id} is closed")
        return session

    async def join(
        self, session_id: str, join_code: str, device_id: str, device_name: str
    ) -> JoinSessionResponse:
        session = await self.get_record(session_id, allow_closed=False)
        expected = self._digest_join_code(session_id, join_code.upper())
        if not secrets.compare_digest(expected, session.join_code_digest):
            raise InvalidJoinCode("join code is invalid")
        if (
            device_id not in session.devices
            and len(session.devices) >= self._settings.max_devices_per_session
        ):
            raise SessionFull("session has reached its device limit")
        await self.repository.add_device(session_id, device_id, device_name)
        return JoinSessionResponse(
            session_id=session_id,
            device_id=device_id,
            expires_at=session.expires_at,
        )

    async def status(self, session_id: str) -> SessionStatusResponse:
        return self._status(await self.get_record(session_id))

    async def list(self, limit: int) -> list[SessionStatusResponse]:
        return [
            self._status(session)
            for session in await self.repository.list_sessions(limit)
        ]

    async def close(self, session_id: str) -> CloseSessionResponse:
        await self.get_record(session_id)
        ended_at = datetime.now(UTC)
        await self.repository.close(session_id, ended_at)
        return CloseSessionResponse(session_id=session_id, ended_at=ended_at)

    async def build_summary(self, session_id: str) -> SessionSummaryResponse:
        session = await self.get_record(session_id)
        results = await self.repository.results(session_id, 100_000)
        angles = [
            float(result["primary_angle_degrees"])
            for result in results
            if result.get("primary_angle_degrees") is not None
        ]
        total_reps = max((int(result.get("rep_count", 0)) for result in results), default=0)
        end = session.ended_at or datetime.now(UTC)
        saved = await self.repository.summary(session_id)
        return SessionSummaryResponse(
            session_id=session_id,
            exercise=session.exercise,
            duration_seconds=max(0, int((end - session.created_at).total_seconds())),
            total_reps=total_reps,
            angle_min=min(angles) if angles else None,
            angle_max=max(angles) if angles else None,
            ai_summary=str(saved["text"]) if saved and saved.get("text") else None,
            started_at=session.created_at,
            ended_at=session.ended_at,
        )

    async def delete(self, session_id: str) -> None:
        await self.repository.delete(session_id)
