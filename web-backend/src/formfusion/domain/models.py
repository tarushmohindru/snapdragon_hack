from dataclasses import dataclass, field
from datetime import UTC, datetime


@dataclass(slots=True)
class SessionRecord:
    session_id: str
    join_code_digest: str
    exercise: str
    created_at: datetime
    expires_at: datetime
    devices: set[str] = field(default_factory=set)
    calibrated: bool = False
    calibration_reprojection_error: float | None = None
    latest_result_at: datetime | None = None
    ended_at: datetime | None = None

    @property
    def expired(self) -> bool:
        return self.ended_at is None and datetime.now(UTC) >= self.expires_at
