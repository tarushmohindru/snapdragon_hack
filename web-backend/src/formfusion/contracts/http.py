from datetime import datetime

from pydantic import Field, model_validator

from formfusion.contracts.common import StrictModel


class CreateSessionRequest(StrictModel):
    exercise: str = Field(default="left_bicep_curl", min_length=1, max_length=64)


class CreateSessionResponse(StrictModel):
    session_id: str
    join_code: str
    host_token: str
    expires_at: datetime


class JoinSessionRequest(StrictModel):
    join_code: str = Field(min_length=6, max_length=16)
    device_id: str = Field(min_length=3, max_length=128)


class JoinSessionResponse(StrictModel):
    session_id: str
    device_id: str
    device_token: str
    expires_at: datetime


class SessionStatusResponse(StrictModel):
    session_id: str
    exercise: str
    device_ids: list[str]
    calibrated: bool
    expires_at: datetime


class ProjectionCalibrationRequest(StrictModel):
    device_a: str = Field(min_length=3, max_length=128)
    device_b: str = Field(min_length=3, max_length=128)
    projection_a: list[list[float]]
    projection_b: list[list[float]]
    reprojection_error: float | None = Field(default=None, ge=0.0)

    @model_validator(mode="after")
    def matrices_are_3_by_4(self) -> "ProjectionCalibrationRequest":
        for name, matrix in (
            ("projection_a", self.projection_a),
            ("projection_b", self.projection_b),
        ):
            if len(matrix) != 3 or any(len(row) != 4 for row in matrix):
                raise ValueError(f"{name} must be a 3x4 matrix")
        if self.device_a == self.device_b:
            raise ValueError("calibration devices must be different")
        return self


class CalibrationResponse(StrictModel):
    session_id: str
    calibrated: bool
    quality: str
    reprojection_error: float | None


class CalibrationCaptureResponse(StrictModel):
    session_id: str
    device_id: str
    pair_id: str
    captures_for_device: int
    complete_pairs: int


class FinalizeCalibrationRequest(StrictModel):
    device_a: str = Field(min_length=3, max_length=128)
    device_b: str = Field(min_length=3, max_length=128)
    checkerboard_columns: int = Field(default=9, ge=3, le=30)
    checkerboard_rows: int = Field(default=6, ge=3, le=30)
    square_size: float = Field(default=1.0, gt=0.0, le=1_000.0)
    minimum_pairs: int = Field(default=10, ge=3, le=100)
