from typing import Any

import pytest
from fastapi.testclient import TestClient

from formfusion.config import Settings
from formfusion.contracts.http import AiResponse, CalibrationResponse
from formfusion.contracts.websocket import PoseResult
from formfusion.main import create_app


class FakeMlClient:
    async def close(self) -> None:
        return None

    async def health(self) -> bool:
        return True

    async def upload_capture(
        self, session_id: str, device_id: str, pair_id: str, image: Any
    ) -> CalibrationResponse:
        await image.read()
        return CalibrationResponse(
            session_id=session_id, calibrated=False, complete_pairs=1
        )

    async def import_calibration(self, session_id: str, request: Any) -> CalibrationResponse:
        return CalibrationResponse(
            session_id=session_id,
            calibrated=True,
            complete_pairs=10,
            calibration_id=request.calibration_id,
            reprojection_error=request.reprojection_error,
        )

    async def calibration_status(self, session_id: str) -> CalibrationResponse:
        return CalibrationResponse(
            session_id=session_id,
            calibrated=True,
            complete_pairs=10,
            calibration_id="test-calibration",
            reprojection_error=0.08,
        )

    async def finalize_calibration(self, session_id: str, request: Any) -> CalibrationResponse:
        return CalibrationResponse(
            session_id=session_id,
            calibrated=True,
            complete_pairs=request.minimum_pairs,
            calibration_id="test-calibration",
            reprojection_error=0.08,
        )

    async def reconstruct(self, session_id: str, exercise: str, pair: Any) -> PoseResult:
        first, second = pair.first, pair.second
        joints = {
            str(point.id): {
                "x": point.x,
                "y": point.y,
                "z": 1.0,
                "confidence": point.confidence,
                "observations": 2,
            }
            for point in first.person.keypoints
        }
        return PoseResult.model_validate(
            {
                "session_id": session_id,
                "captured_at_ms": max(first.captured_at_ms, second.captured_at_ms),
                "joints_3d": joints,
                "angles": [],
                "primary_angle_degrees": 90.0,
                "rep_count": 1,
                "movement_state": "contracted",
                "form_quality": "good",
                "metadata": {
                    "coordinate_system": "camera_a_world_right_handed",
                    "units": "meters",
                    "calibration_id": "test-calibration",
                    "reprojection_error": 0.08,
                    "source_frame_ids": {
                        first.device_id: first.frame_id,
                        second.device_id: second.frame_id,
                    },
                    "source_timestamps_ms": {
                        first.device_id: first.captured_at_ms,
                        second.device_id: second.captured_at_ms,
                    },
                    "pairing_delta_ms": pair.delta_ms,
                    "processing_time_ms": 1.0,
                },
            }
        )

    async def realtime_feedback(self, payload: dict[str, object]) -> AiResponse:
        return AiResponse(text="Keep your elbow stable.", provider="test", model="test")

    async def summary(self, payload: dict[str, object]) -> AiResponse:
        return AiResponse(text="Strong controlled session.", provider="test", model="test")

    async def delete_session(self, session_id: str) -> None:
        return None


@pytest.fixture
def settings(tmp_path) -> Settings:
    return Settings(
        environment="test",
        allowed_origins=["http://testserver"],
        frame_queue_capacity=4,
        frame_sync_tolerance_ms=60,
        database_path=tmp_path / "formfusion.sqlite3",
    )


@pytest.fixture
def client(settings: Settings):
    app = create_app(settings)
    with TestClient(app) as test_client:
        original = app.state.ml
        app.state.ml = FakeMlClient()
        yield test_client
        app.state.ml = original
