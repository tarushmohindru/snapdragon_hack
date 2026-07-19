from typing import Literal

from pydantic import Field, model_validator

from formfusion.contracts.common import ClientRole, ImageMetadata, Keypoint, StrictModel


class DeviceHello(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["device.hello"] = "device.hello"
    session_id: str
    device_id: str | None = None
    role: ClientRole


class PersonPose(StrictModel):
    track_id: int = Field(default=1, ge=0)
    keypoints: list[Keypoint] = Field(min_length=1, max_length=133)

    @model_validator(mode="after")
    def unique_keypoint_ids(self) -> "PersonPose":
        ids = [point.id for point in self.keypoints]
        if len(ids) != len(set(ids)):
            raise ValueError("keypoint IDs must be unique")
        return self


class PoseFrame(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["pose.frame"] = "pose.frame"
    session_id: str
    device_id: str
    frame_id: int = Field(ge=0)
    captured_at_ms: int = Field(ge=0)
    image: ImageMetadata
    person: PersonPose


class ClientPing(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["ping"] = "ping"


class ErrorMessage(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["error"] = "error"
    code: str
    message: str
    request_id: str | None = None


class StatusMessage(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["session.status"] = "session.status"
    session_id: str
    status: str
    connected_devices: int
    calibrated: bool


class FrameAck(StrictModel):
    schema_version: Literal[1] = 1
    type: Literal["frame.ack"] = "frame.ack"
    frame_id: int
    status: Literal["queued", "paired", "dropped"]


class Joint3D(StrictModel):
    x: float
    y: float
    z: float
    confidence: float
    observations: int


class AngleMetric(StrictModel):
    name: str
    degrees: float
    joint_ids: tuple[int, int, int]


class ResultMetadata(StrictModel):
    coordinate_system: str
    units: str
    calibration_id: str
    reprojection_error: float | None
    source_frame_ids: dict[str, int]
    source_timestamps_ms: dict[str, int]
    pairing_delta_ms: int
    processing_time_ms: float


class PoseResult(StrictModel):
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
    form_feedback: str | None = None
    metadata: ResultMetadata
