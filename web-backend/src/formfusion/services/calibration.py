import asyncio
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path

import anyio
import cv2
import numpy as np
from fastapi import UploadFile

from formfusion.config import Settings
from formfusion.contracts.http import FinalizeCalibrationRequest
from formfusion.domain.errors import CalibrationFailed, PayloadTooLarge
from formfusion.pipeline.triangulation import build_projection_matrix

SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


@dataclass(frozen=True, slots=True)
class CalibrationResult:
    projection_a: list[list[float]]
    projection_b: list[list[float]]
    reprojection_error: float
    complete_pairs: int


class CalibrationService:
    def __init__(self, settings: Settings) -> None:
        self._root = settings.calibration_root.resolve()
        self._max_bytes = settings.max_calibration_image_bytes
        self._captures: dict[str, dict[str, dict[str, Path]]] = {}
        self._lock = asyncio.Lock()

    @staticmethod
    def _validate_identifier(value: str, label: str) -> None:
        if not SAFE_IDENTIFIER.fullmatch(value):
            raise CalibrationFailed(f"{label} contains unsupported characters")

    async def save_capture(
        self,
        session_id: str,
        device_id: str,
        pair_id: str,
        image: UploadFile,
    ) -> tuple[int, int]:
        self._validate_identifier(device_id, "device_id")
        self._validate_identifier(pair_id, "pair_id")
        if image.content_type not in {"image/jpeg", "image/png", "image/webp"}:
            raise CalibrationFailed("calibration capture must be JPEG, PNG, or WebP")

        safe_device = hashlib.sha256(device_id.encode()).hexdigest()[:24]
        safe_pair = hashlib.sha256(pair_id.encode()).hexdigest()[:24]
        directory = self._root / session_id / safe_device
        path = directory / f"{safe_pair}.image"
        await anyio.Path(directory).mkdir(parents=True, exist_ok=True)

        bytes_written = 0
        async with await anyio.open_file(path, "wb") as destination:
            while chunk := await image.read(1024 * 1024):
                bytes_written += len(chunk)
                if bytes_written > self._max_bytes:
                    await destination.aclose()
                    await anyio.Path(path).unlink(missing_ok=True)
                    raise PayloadTooLarge("calibration image exceeds configured size limit")
                await destination.write(chunk)
        await image.close()
        if bytes_written == 0:
            await anyio.Path(path).unlink(missing_ok=True)
            raise CalibrationFailed("calibration image is empty")

        async with self._lock:
            session = self._captures.setdefault(session_id, {})
            device = session.setdefault(device_id, {})
            device[pair_id] = path
            complete_pairs = self._complete_pair_count(session)
            return len(device), complete_pairs

    @staticmethod
    def _complete_pair_count(session: dict[str, dict[str, Path]]) -> int:
        if len(session) < 2:
            return 0
        devices = list(session.values())[:2]
        return len(set(devices[0]) & set(devices[1]))

    async def finalize(
        self, session_id: str, request: FinalizeCalibrationRequest
    ) -> CalibrationResult:
        async with self._lock:
            session = self._captures.get(session_id, {})
            captures_a = dict(session.get(request.device_a, {}))
            captures_b = dict(session.get(request.device_b, {}))
        pair_ids = sorted(set(captures_a) & set(captures_b))
        if len(pair_ids) < request.minimum_pairs:
            raise CalibrationFailed(
                f"need {request.minimum_pairs} paired captures; found {len(pair_ids)}"
            )
        return await anyio.to_thread.run_sync(
            self._calibrate,
            request,
            [(captures_a[pair_id], captures_b[pair_id]) for pair_id in pair_ids],
            abandon_on_cancel=True,
        )

    @staticmethod
    def _calibrate(
        request: FinalizeCalibrationRequest,
        pairs: list[tuple[Path, Path]],
    ) -> CalibrationResult:
        pattern = (request.checkerboard_columns, request.checkerboard_rows)
        object_template = np.zeros((pattern[0] * pattern[1], 3), dtype=np.float32)
        object_template[:, :2] = np.mgrid[0 : pattern[0], 0 : pattern[1]].T.reshape(-1, 2)
        object_template *= request.square_size

        object_points: list[np.ndarray] = []
        image_points_a: list[np.ndarray] = []
        image_points_b: list[np.ndarray] = []
        image_size: tuple[int, int] | None = None
        criteria = (
            cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER,
            30,
            0.001,
        )

        for path_a, path_b in pairs:
            grayscale_a = cv2.imread(str(path_a), cv2.IMREAD_GRAYSCALE)
            grayscale_b = cv2.imread(str(path_b), cv2.IMREAD_GRAYSCALE)
            if grayscale_a is None or grayscale_b is None:
                continue
            if grayscale_a.shape != grayscale_b.shape:
                continue
            current_size = (grayscale_a.shape[1], grayscale_a.shape[0])
            if image_size is not None and current_size != image_size:
                continue
            found_a, corners_a = cv2.findChessboardCorners(grayscale_a, pattern)
            found_b, corners_b = cv2.findChessboardCorners(grayscale_b, pattern)
            if not found_a or not found_b:
                continue
            image_size = current_size
            refined_a = cv2.cornerSubPix(grayscale_a, corners_a, (11, 11), (-1, -1), criteria)
            refined_b = cv2.cornerSubPix(grayscale_b, corners_b, (11, 11), (-1, -1), criteria)
            object_points.append(object_template.copy())
            image_points_a.append(refined_a)
            image_points_b.append(refined_b)

        if image_size is None or len(object_points) < request.minimum_pairs:
            raise CalibrationFailed(
                "not enough paired captures contained a detectable checkerboard"
            )

        error_a, camera_a, distortion_a, _, _ = cv2.calibrateCamera(
            object_points,
            image_points_a,
            image_size,
            None,
            None,
        )
        error_b, camera_b, distortion_b, _, _ = cv2.calibrateCamera(
            object_points,
            image_points_b,
            image_size,
            None,
            None,
        )
        stereo_error, _, _, _, _, rotation, translation, _, _ = cv2.stereoCalibrate(
            object_points,
            image_points_a,
            image_points_b,
            camera_a,
            distortion_a,
            camera_b,
            distortion_b,
            image_size,
            criteria=criteria,
            flags=cv2.CALIB_FIX_INTRINSIC,
        )
        projection_a = build_projection_matrix(
            np.asarray(camera_a, dtype=np.float64),
            np.eye(3, dtype=np.float64),
            np.zeros(3, dtype=np.float64),
        )
        projection_b = build_projection_matrix(
            np.asarray(camera_b, dtype=np.float64),
            np.asarray(rotation, dtype=np.float64),
            np.asarray(translation, dtype=np.float64),
        )
        combined_error = float(max(error_a, error_b, stereo_error))
        return CalibrationResult(
            projection_a=projection_a.tolist(),
            projection_b=projection_b.tolist(),
            reprojection_error=combined_error,
            complete_pairs=len(object_points),
        )

    async def delete_session(self, session_id: str) -> None:
        async with self._lock:
            self._captures.pop(session_id, None)
        session_path = self._root / session_id
        if await anyio.Path(session_path).exists():
            import shutil

            await anyio.to_thread.run_sync(shutil.rmtree, session_path)
