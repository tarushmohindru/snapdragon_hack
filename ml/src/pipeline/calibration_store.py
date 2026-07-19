import hashlib
import json
import shutil
import uuid
from pathlib import Path

import cv2
import numpy as np

from src.calibration.calibrate import calibrate_camera
from src.calibration.stereo_calibrate import stereo_calibrate
from src.contracts import ProjectionCalibration


class CalibrationStore:
    """File-backed calibration capture and parameter store owned by the ML service."""

    def __init__(self, root: Path) -> None:
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def _session_dir(self, session_id: str) -> Path:
        return self.root / hashlib.sha256(session_id.encode()).hexdigest()[:32]

    def save_capture(
        self,
        session_id: str,
        device_id: str,
        pair_id: str,
        content: bytes,
    ) -> tuple[int, int]:
        session_dir = self._session_dir(session_id)
        device_dir = session_dir / "captures" / hashlib.sha256(device_id.encode()).hexdigest()[:24]
        device_dir.mkdir(parents=True, exist_ok=True)
        path = device_dir / f"{hashlib.sha256(pair_id.encode()).hexdigest()[:24]}.image"
        path.write_bytes(content)
        index_path = session_dir / "capture-index.json"
        index = json.loads(index_path.read_text()) if index_path.exists() else {}
        index.setdefault(device_id, {})[pair_id] = str(path)
        index_path.write_text(json.dumps(index, indent=2))
        sets = [set(captures) for captures in index.values()]
        complete = len(set.intersection(*sets)) if len(sets) >= 2 else 0
        return len(index[device_id]), complete

    def status(self, session_id: str) -> tuple[dict[str, int], int, ProjectionCalibration | None]:
        session_dir = self._session_dir(session_id)
        index_path = session_dir / "capture-index.json"
        index = json.loads(index_path.read_text()) if index_path.exists() else {}
        counts = {device: len(captures) for device, captures in index.items()}
        sets = [set(captures) for captures in index.values()]
        complete = len(set.intersection(*sets)) if len(sets) >= 2 else 0
        return counts, complete, self.load(session_id)

    def save(self, session_id: str, calibration: ProjectionCalibration) -> None:
        session_dir = self._session_dir(session_id)
        session_dir.mkdir(parents=True, exist_ok=True)
        (session_dir / "calibration.json").write_text(calibration.model_dump_json(indent=2))

    def load(self, session_id: str) -> ProjectionCalibration | None:
        path = self._session_dir(session_id) / "calibration.json"
        return (
            ProjectionCalibration.model_validate_json(path.read_text()) if path.exists() else None
        )

    def finalize(
        self,
        session_id: str,
        device_a: str,
        device_b: str,
        checkerboard: tuple[int, int],
        square_size: float,
        minimum_pairs: int,
        allow_approximate: bool = False,
        approximate_baseline_cm: float = 30.0,
    ) -> ProjectionCalibration:
        index_path = self._session_dir(session_id) / "capture-index.json"
        if not index_path.exists():
            raise ValueError("no calibration captures have been uploaded")
        index = json.loads(index_path.read_text())
        common = sorted(set(index.get(device_a, {})) & set(index.get(device_b, {})))
        if len(common) < minimum_pairs:
            raise ValueError(f"need {minimum_pairs} paired captures; found {len(common)}")
        paths_a = [Path(index[device_a][pair_id]) for pair_id in common]
        paths_b = [Path(index[device_b][pair_id]) for pair_id in common]
        try:
            intrinsics_a = calibrate_camera(paths_a, checkerboard, square_size, minimum_pairs)
            intrinsics_b = calibrate_camera(paths_b, checkerboard, square_size, minimum_pairs)
        except ValueError as calibration_error:
            if not allow_approximate:
                raise
            image_a = cv2.imread(str(paths_a[0]))
            image_b = cv2.imread(str(paths_b[0]))
            if image_a is None or image_b is None:
                raise ValueError(
                    "approximate calibration could not read capture images"
                ) from calibration_error

            def camera_matrix(image: np.ndarray) -> list[list[float]]:
                height, width = image.shape[:2]
                focal = float(max(width, height))
                return [
                    [focal, 0.0, width / 2.0],
                    [0.0, focal, height / 2.0],
                    [0.0, 0.0, 1.0],
                ]

            calibration = ProjectionCalibration(
                calibration_id=f"approximate-{uuid.uuid4()}",
                device_a=device_a,
                device_b=device_b,
                camera_matrix_a=camera_matrix(image_a),
                distortion_a=[0.0, 0.0, 0.0, 0.0, 0.0],
                camera_matrix_b=camera_matrix(image_b),
                distortion_b=[0.0, 0.0, 0.0, 0.0, 0.0],
                rotation_a_to_b=np.eye(3).tolist(),
                translation_a_to_b=[approximate_baseline_cm, 0.0, 0.0],
                units="centimeters",
                reprojection_error=None,
            )
            self.save(session_id, calibration)
            return calibration
        stereo = stereo_calibrate(
            list(zip(paths_a, paths_b, strict=True)),
            {
                "camera_matrix": np.asarray(intrinsics_a["camera_matrix"]),
                "dist_coeffs": np.asarray(intrinsics_a["dist_coeffs"]),
            },
            {
                "camera_matrix": np.asarray(intrinsics_b["camera_matrix"]),
                "dist_coeffs": np.asarray(intrinsics_b["dist_coeffs"]),
            },
            checkerboard,
            square_size,
            minimum_pairs,
        )
        calibration = ProjectionCalibration(
            calibration_id=str(uuid.uuid4()),
            device_a=device_a,
            device_b=device_b,
            camera_matrix_a=np.asarray(stereo["camera_matrix_a"]).tolist(),
            distortion_a=np.asarray(stereo["distortion_a"]).reshape(-1).tolist(),
            camera_matrix_b=np.asarray(stereo["camera_matrix_b"]).tolist(),
            distortion_b=np.asarray(stereo["distortion_b"]).reshape(-1).tolist(),
            rotation_a_to_b=np.asarray(stereo["rotation_a_to_b"]).tolist(),
            translation_a_to_b=np.asarray(stereo["translation_a_to_b"]).reshape(-1).tolist(),
            reprojection_error=float(stereo["reprojection_error"]),
        )
        self.save(session_id, calibration)
        return calibration

    def delete(self, session_id: str) -> None:
        shutil.rmtree(self._session_dir(session_id), ignore_errors=True)
