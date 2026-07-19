import math
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Landmark2D(Contract):
    id: int = Field(ge=0, le=1_024)
    x: float
    y: float
    confidence: float = Field(ge=0.0, le=1.0)

    @model_validator(mode="after")
    def finite(self) -> "Landmark2D":
        if not math.isfinite(self.x) or not math.isfinite(self.y):
            raise ValueError("landmark coordinates must be finite")
        return self


class CameraObservation(Contract):
    device_id: str = Field(min_length=3, max_length=128)
    frame_id: int = Field(ge=0)
    captured_at_ms: int = Field(ge=0)
    image_width: int = Field(ge=1, le=16_384)
    image_height: int = Field(ge=1, le=16_384)
    rotation_degrees: Literal[0, 90, 180, 270] = 0
    mirrored: bool = False
    track_id: int = Field(default=1, ge=0)
    landmarks: list[Landmark2D] = Field(min_length=1, max_length=133)

    @model_validator(mode="after")
    def unique_landmarks(self) -> "CameraObservation":
        ids = [landmark.id for landmark in self.landmarks]
        if len(ids) != len(set(ids)):
            raise ValueError("landmark IDs must be unique per camera observation")
        return self


class ReconstructionRequest(Contract):
    schema_version: Literal[1] = 1
    session_id: str = Field(min_length=3, max_length=128)
    exercise: str = Field(default="left_bicep_curl", min_length=1, max_length=64)
    observations: list[CameraObservation] = Field(min_length=2, max_length=8)

    @model_validator(mode="after")
    def unique_devices(self) -> "ReconstructionRequest":
        devices = [observation.device_id for observation in self.observations]
        if len(devices) != len(set(devices)):
            raise ValueError("one synchronized observation per device is required")
        return self


class Joint3D(Contract):
    x: float
    y: float
    z: float
    confidence: float = Field(ge=0.0, le=1.0)
    observations: int = Field(ge=2)


class AngleMetric(Contract):
    name: str
    degrees: float
    joint_ids: tuple[int, int, int]


class ResultMetadata(Contract):
    coordinate_system: Literal["camera_a_world_right_handed"] = "camera_a_world_right_handed"
    units: str
    calibration_id: str
    reprojection_error: float | None
    source_frame_ids: dict[str, int]
    source_timestamps_ms: dict[str, int]
    pairing_delta_ms: int
    processing_time_ms: float


class ReconstructionResult(Contract):
    schema_version: Literal[1] = 1
    type: Literal["pose.result"] = "pose.result"
    session_id: str
    captured_at_ms: int
    joints_3d: dict[str, Joint3D]
    angles: list[AngleMetric]
    primary_angle_degrees: float | None
    rep_count: int
    movement_state: str
    form_quality: Literal["good", "check", "unknown"]
    metadata: ResultMetadata


class ProjectionCalibration(Contract):
    calibration_id: str
    device_a: str
    device_b: str
    camera_matrix_a: list[list[float]]
    distortion_a: list[float]
    camera_matrix_b: list[list[float]]
    distortion_b: list[float]
    rotation_a_to_b: list[list[float]]
    translation_a_to_b: list[float]
    world_transform: list[list[float]] = Field(
        default_factory=lambda: [
            [1.0, 0.0, 0.0, 0.0],
            [0.0, 1.0, 0.0, 0.0],
            [0.0, 0.0, 1.0, 0.0],
            [0.0, 0.0, 0.0, 1.0],
        ]
    )
    units: str = "centimeters"
    reprojection_error: float | None = Field(default=None, ge=0.0)


class CalibrationStatus(Contract):
    session_id: str
    captures_by_device: dict[str, int]
    complete_pairs: int
    calibrated: bool
    calibration_id: str | None = None
    reprojection_error: float | None = None


class FinalizeCalibrationRequest(Contract):
    device_a: str
    device_b: str
    checkerboard_columns: int = Field(default=9, ge=3, le=30)
    checkerboard_rows: int = Field(default=6, ge=3, le=30)
    square_size: float = Field(default=2.5, gt=0.0)
    minimum_pairs: int = Field(default=10, ge=3, le=100)


class RealtimeFeedbackRequest(Contract):
    exercise: str
    primary_angle_degrees: float | None
    rep_count: int
    movement_state: str
    form_quality: str
    language: str = "English"


class SessionSummaryRequest(Contract):
    exercise: str
    total_reps: int
    duration_seconds: int
    angle_min: float | None = None
    angle_max: float | None = None
    form_notes: list[str] = Field(default_factory=list)
    language: str = "English"


class AiResponse(Contract):
    text: str
    provider: str
    model: str
