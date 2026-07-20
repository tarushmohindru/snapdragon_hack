import numpy as np
from fastapi.testclient import TestClient

from src.api import app


def test_import_calibration_and_reconstruct(monkeypatch, tmp_path) -> None:
    monkeypatch.setenv("FORMFUSION_ML_ENVIRONMENT", "test")
    monkeypatch.setenv("FORMFUSION_ML_SERVICE_KEY", "test-key")
    monkeypatch.setenv("FORMFUSION_ML_DATA_ROOT", str(tmp_path))
    from src.settings import get_settings

    get_settings.cache_clear()
    headers = {"X-ML-Service-Key": "test-key"}
    with TestClient(app) as client:
        calibration = {
            "calibration_id": "cal-1",
            "device_a": "phone-a",
            "device_b": "phone-b",
            "camera_matrix_a": np.eye(3).tolist(),
            "distortion_a": [0, 0, 0, 0, 0],
            "camera_matrix_b": np.eye(3).tolist(),
            "distortion_b": [0, 0, 0, 0, 0],
            "rotation_a_to_b": np.eye(3).tolist(),
            "translation_a_to_b": [-1, 0, 0],
            "units": "meters",
        }
        response = client.put(
            "/v1/sessions/session-1/calibration",
            headers=headers,
            json=calibration,
        )
        assert response.status_code == 200
        payload = {
            "session_id": "session-1",
            "exercise": "bicep_curls",
            "observations": [
                {
                    "device_id": "phone-a",
                    "frame_id": 1,
                    "captured_at_ms": 1000,
                    "image_width": 640,
                    "image_height": 480,
                    "landmarks": [
                        {"id": 5, "x": 0.04, "y": 0.24, "confidence": 0.9},
                        {"id": 7, "x": 0.04, "y": 0.16, "confidence": 0.9},
                        {"id": 9, "x": 0.09, "y": 0.11, "confidence": 0.9},
                    ],
                },
                {
                    "device_id": "phone-b",
                    "frame_id": 2,
                    "captured_at_ms": 1010,
                    "image_width": 640,
                    "image_height": 480,
                    "landmarks": [
                        {"id": 5, "x": -0.16, "y": 0.24, "confidence": 0.9},
                        {"id": 7, "x": -0.16, "y": 0.16, "confidence": 0.9},
                        {"id": 9, "x": -0.11, "y": 0.11, "confidence": 0.9},
                    ],
                },
            ],
        }
        result = client.post("/v1/reconstruct", headers=headers, json=payload)
        assert result.status_code == 200
        assert set(result.json()["joints_3d"]) == {"5", "7", "9"}
