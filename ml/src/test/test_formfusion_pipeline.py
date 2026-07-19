import numpy as np
import pytest

from src.contracts import (
    CameraObservation,
    Landmark2D,
    ProjectionCalibration,
    ReconstructionRequest,
)
from src.pipeline.formfusion_pipeline import FormFusionPipeline


def project(point: np.ndarray, projection: np.ndarray) -> tuple[float, float]:
    homogeneous = projection @ np.append(point, 1.0)
    return float(homogeneous[0] / homogeneous[2]), float(homogeneous[1] / homogeneous[2])


def test_pipeline_returns_world_xyz_confidence_and_source_metadata() -> None:
    camera = np.eye(3)
    projection_a = np.hstack((camera, np.zeros((3, 1))))
    projection_b = np.hstack((camera, np.asarray([[-1.0], [0.0], [0.0]])))
    calibration = ProjectionCalibration(
        calibration_id="calibration-1",
        device_a="phone-a",
        device_b="phone-b",
        camera_matrix_a=camera.tolist(),
        distortion_a=[0, 0, 0, 0, 0],
        camera_matrix_b=camera.tolist(),
        distortion_b=[0, 0, 0, 0, 0],
        rotation_a_to_b=np.eye(3).tolist(),
        translation_a_to_b=[-1, 0, 0],
        units="meters",
        reprojection_error=0.08,
    )
    points = {
        5: np.asarray([0.2, 1.2, 5.0]),
        7: np.asarray([0.2, 0.8, 5.0]),
        9: np.asarray([0.45, 0.55, 5.0]),
    }

    def observation(device: str, projection: np.ndarray, frame_id: int, timestamp: int):
        return CameraObservation(
            device_id=device,
            frame_id=frame_id,
            captured_at_ms=timestamp,
            image_width=640,
            image_height=480,
            landmarks=[
                Landmark2D(
                    id=joint_id,
                    x=project(point, projection)[0],
                    y=project(point, projection)[1],
                    confidence=0.9,
                )
                for joint_id, point in points.items()
            ],
        )

    request = ReconstructionRequest(
        session_id="session-1",
        exercise="bicep_curls",
        observations=[
            observation("phone-a", projection_a, 10, 1_000),
            observation("phone-b", projection_b, 20, 1_012),
        ],
    )
    result = FormFusionPipeline("session-1", "bicep_curls", calibration, 0.1).process(request)

    assert set(result.joints_3d) == {"5", "7", "9"}
    assert result.joints_3d["7"].x == pytest.approx(points[7][0], abs=1e-7)
    assert result.joints_3d["7"].y == pytest.approx(points[7][1], abs=1e-7)
    assert result.joints_3d["7"].z == pytest.approx(points[7][2], abs=1e-7)
    assert result.joints_3d["7"].confidence == pytest.approx(0.9)
    assert result.metadata.source_frame_ids == {"phone-a": 10, "phone-b": 20}
    assert result.metadata.source_timestamps_ms == {"phone-a": 1_000, "phone-b": 1_012}
    assert result.metadata.pairing_delta_ms == 12
    assert result.metadata.units == "meters"
    assert result.primary_angle_degrees is not None
