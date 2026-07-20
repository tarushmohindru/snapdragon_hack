from datetime import datetime
from typing import Literal

from pydantic import Field

from formfusion.contracts.common import StrictModel


class CreateSessionRequest(StrictModel):
    exercise: Literal[
        "squats", "deadlifts", "bench_press", "bicep_curls", "shoulder_press"
    ] = "bicep_curls"


class CreateSessionResponse(StrictModel):
    session_id: str
    join_code: str
    expires_at: datetime


class JoinSessionRequest(StrictModel):
    join_code: str = Field(min_length=6, max_length=16)
    device_id: str = Field(min_length=3, max_length=128)
    device_name: str = Field(default="Android phone", min_length=1, max_length=128)


class JoinSessionResponse(StrictModel):
    session_id: str
    device_id: str
    expires_at: datetime


class ResolveSessionResponse(StrictModel):
    session_id: str


class SessionStatusResponse(StrictModel):
    session_id: str
    exercise: str
    device_ids: list[str]
    calibrated: bool
    calibration_reprojection_error: float | None = None
    latest_result_at: datetime | None = None
    started_at: datetime
    ended_at: datetime | None = None
    expires_at: datetime


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
    square_size: float = Field(default=2.5, gt=0.0, le=1_000.0)
    minimum_pairs: int = Field(default=10, ge=3, le=100)


class CalibrationResponse(StrictModel):
    session_id: str
    calibrated: bool
    complete_pairs: int
    calibration_id: str | None = None
    reprojection_error: float | None = None


class ProjectionCalibrationRequest(StrictModel):
    calibration_id: str
    device_a: str
    device_b: str
    camera_matrix_a: list[list[float]]
    distortion_a: list[float]
    camera_matrix_b: list[list[float]]
    distortion_b: list[float]
    rotation_a_to_b: list[list[float]]
    translation_a_to_b: list[float]
    world_transform: list[list[float]]
    units: str = "centimeters"
    reprojection_error: float | None = None


class FeedbackRequest(StrictModel):
    language: str = Field(default="English", min_length=2, max_length=64)


class AiResponse(StrictModel):
    text: str
    provider: str
    model: str


class SessionSummaryResponse(StrictModel):
    session_id: str
    exercise: str
    duration_seconds: int
    total_reps: int
    angle_min: float | None
    angle_max: float | None
    ai_summary: str | None
    started_at: datetime
    ended_at: datetime | None


class CloseSessionResponse(StrictModel):
    session_id: str
    ended_at: datetime


class SessionResultsResponse(StrictModel):
    session_id: str
    results: list[dict[str, object]]
