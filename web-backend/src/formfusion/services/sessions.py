import hashlib
import hmac
import secrets
import string
import uuid
from datetime import UTC, datetime, timedelta

from formfusion.config import Settings
from formfusion.contracts.common import ClientRole
from formfusion.contracts.http import (
    CreateSessionResponse,
    JoinSessionResponse,
    SessionStatusResponse,
)
from formfusion.domain.errors import InvalidJoinCode, SessionExpired, SessionFull
from formfusion.domain.models import SessionRecord
from formfusion.repositories.memory import MemorySessionRepository
from formfusion.services.auth import TokenClaims, TokenService


class SessionService:
    def __init__(
        self,
        repository: MemorySessionRepository,
        tokens: TokenService,
        settings: Settings,
    ) -> None:
        self._repository = repository
        self._tokens = tokens
        self._settings = settings

    def _digest_join_code(self, session_id: str, join_code: str) -> str:
        return hmac.new(
            self._settings.jwt_secret.encode(),
            f"{session_id}:{join_code}".encode(),
            hashlib.sha256,
        ).hexdigest()

    async def create(self, exercise: str) -> CreateSessionResponse:
        session_id = str(uuid.uuid4())
        alphabet = string.ascii_uppercase + string.digits
        join_code = "".join(secrets.choice(alphabet) for _ in range(8))
        now = datetime.now(UTC)
        expires_at = now + timedelta(seconds=self._settings.session_ttl_seconds)
        session = SessionRecord(
            session_id=session_id,
            join_code_digest=self._digest_join_code(session_id, join_code),
            exercise=exercise,
            created_at=now,
            expires_at=expires_at,
        )
        await self._repository.create(session)
        return CreateSessionResponse(
            session_id=session_id,
            join_code=join_code,
            host_token=self._tokens.issue(session_id, ClientRole.HOST),
            expires_at=expires_at,
        )

    async def get_record(self, session_id: str) -> SessionRecord:
        session = await self._repository.get(session_id)
        if session.expired:
            raise SessionExpired(f"session {session_id} has expired")
        return session

    async def join(self, session_id: str, join_code: str, device_id: str) -> JoinSessionResponse:
        session = await self.get_record(session_id)
        expected = self._digest_join_code(session_id, join_code.upper())
        if not secrets.compare_digest(expected, session.join_code_digest):
            raise InvalidJoinCode("join code is invalid")
        if (
            device_id not in session.devices
            and len(session.devices) >= self._settings.max_devices_per_session
        ):
            raise SessionFull("session has reached its device limit")
        session.devices.add(device_id)
        return JoinSessionResponse(
            session_id=session_id,
            device_id=device_id,
            device_token=self._tokens.issue(session_id, ClientRole.DEVICE, device_id),
            expires_at=session.expires_at,
        )

    async def status(self, session_id: str) -> SessionStatusResponse:
        session = await self.get_record(session_id)
        return SessionStatusResponse(
            session_id=session.session_id,
            exercise=session.exercise,
            device_ids=sorted(session.devices),
            calibrated=session.calibrated,
            expires_at=session.expires_at,
        )

    async def authorize_host(self, session_id: str, authorization: str | None) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            from formfusion.domain.errors import Unauthorized

            raise Unauthorized("missing bearer token")
        claims = self._tokens.verify(authorization.removeprefix("Bearer ").strip())
        if claims.session_id != session_id or claims.role is not ClientRole.HOST:
            from formfusion.domain.errors import Unauthorized

            raise Unauthorized("host token required")

    async def authorize_client(
        self,
        session_id: str,
        authorization: str | None,
    ) -> TokenClaims:
        from formfusion.domain.errors import Unauthorized

        if not authorization or not authorization.startswith("Bearer "):
            raise Unauthorized("missing bearer token")
        claims = self._tokens.verify(authorization.removeprefix("Bearer ").strip())
        if claims.session_id != session_id:
            raise Unauthorized("token does not belong to this session")
        await self.get_record(session_id)
        return claims

    async def delete(self, session_id: str) -> None:
        await self._repository.delete(session_id)
