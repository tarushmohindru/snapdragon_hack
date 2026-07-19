from pathlib import Path

import cv2
import numpy as np


def calibrate_camera(
    image_paths: list[Path],
    checkerboard: tuple[int, int] = (9, 6),
    square_size: float = 2.5,
    minimum_images: int = 8,
) -> dict[str, np.ndarray | float | int]:
    """Calculate one camera's intrinsics from checkerboard images."""
    object_template = np.zeros((checkerboard[0] * checkerboard[1], 3), np.float32)
    object_template[:, :2] = np.mgrid[0 : checkerboard[0], 0 : checkerboard[1]].T.reshape(-1, 2)
    object_template *= square_size
    object_points: list[np.ndarray] = []
    image_points: list[np.ndarray] = []
    image_size: tuple[int, int] | None = None
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)

    for image_path in image_paths:
        grayscale = cv2.imread(str(image_path), cv2.IMREAD_GRAYSCALE)
        if grayscale is None:
            continue
        current_size = (grayscale.shape[1], grayscale.shape[0])
        if image_size is not None and current_size != image_size:
            continue
        found, corners = cv2.findChessboardCorners(grayscale, checkerboard)
        if not found:
            continue
        image_size = current_size
        object_points.append(object_template.copy())
        image_points.append(cv2.cornerSubPix(grayscale, corners, (11, 11), (-1, -1), criteria))

    if image_size is None or len(object_points) < minimum_images:
        raise ValueError(
            f"need {minimum_images} usable checkerboard images; found {len(object_points)}"
        )
    error, camera_matrix, distortion, _, _ = cv2.calibrateCamera(
        object_points,
        image_points,
        image_size,
        None,
        None,
        flags=cv2.CALIB_FIX_K3,
    )
    return {
        "camera_matrix": camera_matrix,
        "dist_coeffs": distortion,
        "reprojection_error": float(error),
        "usable_images": len(object_points),
        "image_size": np.asarray(image_size),
    }


def save_intrinsics(path: Path, result: dict[str, np.ndarray | float | int]) -> None:
    np.savez(path, **result)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Calibrate one FormFusion camera")
    parser.add_argument("images", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--columns", type=int, default=9)
    parser.add_argument("--rows", type=int, default=6)
    parser.add_argument("--square-size", type=float, default=2.5)
    args = parser.parse_args()
    calibration = calibrate_camera(
        sorted(args.images.glob("*")),
        (args.columns, args.rows),
        args.square_size,
    )
    save_intrinsics(args.output, calibration)
    print(f"Saved intrinsics to {args.output}")
