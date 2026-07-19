from pathlib import Path

import cv2
import numpy as np


def stereo_calibrate(
    paired_paths: list[tuple[Path, Path]],
    intrinsics_a: dict[str, np.ndarray],
    intrinsics_b: dict[str, np.ndarray],
    checkerboard: tuple[int, int] = (9, 6),
    square_size: float = 2.5,
    minimum_pairs: int = 10,
) -> dict[str, np.ndarray | float | int]:
    """Calculate camera-B extrinsics relative to camera A from paired checkerboards."""
    object_template = np.zeros((checkerboard[0] * checkerboard[1], 3), np.float32)
    object_template[:, :2] = np.mgrid[0 : checkerboard[0], 0 : checkerboard[1]].T.reshape(-1, 2)
    object_template *= square_size
    object_points: list[np.ndarray] = []
    image_points_a: list[np.ndarray] = []
    image_points_b: list[np.ndarray] = []
    image_size: tuple[int, int] | None = None
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 30, 0.001)

    for path_a, path_b in paired_paths:
        gray_a = cv2.imread(str(path_a), cv2.IMREAD_GRAYSCALE)
        gray_b = cv2.imread(str(path_b), cv2.IMREAD_GRAYSCALE)
        if gray_a is None or gray_b is None or gray_a.shape != gray_b.shape:
            continue
        current_size = (gray_a.shape[1], gray_a.shape[0])
        if image_size is not None and current_size != image_size:
            continue
        found_a, corners_a = cv2.findChessboardCorners(gray_a, checkerboard)
        found_b, corners_b = cv2.findChessboardCorners(gray_b, checkerboard)
        if not found_a or not found_b:
            continue
        image_size = current_size
        object_points.append(object_template.copy())
        image_points_a.append(cv2.cornerSubPix(gray_a, corners_a, (11, 11), (-1, -1), criteria))
        image_points_b.append(cv2.cornerSubPix(gray_b, corners_b, (11, 11), (-1, -1), criteria))

    if image_size is None or len(object_points) < minimum_pairs:
        raise ValueError(f"need {minimum_pairs} usable stereo pairs; found {len(object_points)}")

    error, camera_a, distortion_a, camera_b, distortion_b, rotation, translation, _, _ = (
        cv2.stereoCalibrate(
            object_points,
            image_points_a,
            image_points_b,
            intrinsics_a["camera_matrix"],
            intrinsics_a["dist_coeffs"],
            intrinsics_b["camera_matrix"],
            intrinsics_b["dist_coeffs"],
            image_size,
            criteria=criteria,
            flags=cv2.CALIB_FIX_INTRINSIC,
        )
    )
    return {
        "camera_matrix_a": camera_a,
        "distortion_a": distortion_a,
        "camera_matrix_b": camera_b,
        "distortion_b": distortion_b,
        "rotation_a_to_b": rotation,
        "translation_a_to_b": translation,
        "reprojection_error": float(error),
        "usable_pairs": len(object_points),
    }


def save_stereo(path: Path, result: dict[str, np.ndarray | float | int]) -> None:
    np.savez(path, **result)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Stereo-calibrate a FormFusion camera pair")
    parser.add_argument("device_a_images", type=Path)
    parser.add_argument("device_b_images", type=Path)
    parser.add_argument("intrinsics_a", type=Path)
    parser.add_argument("intrinsics_b", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    paths_a = {path.name: path for path in args.device_a_images.glob("*")}
    paths_b = {path.name: path for path in args.device_b_images.glob("*")}
    pairs = [(paths_a[name], paths_b[name]) for name in sorted(paths_a.keys() & paths_b.keys())]
    with np.load(args.intrinsics_a) as calibration_a, np.load(args.intrinsics_b) as calibration_b:
        result = stereo_calibrate(pairs, dict(calibration_a), dict(calibration_b))
    save_stereo(args.output, result)
    print(f"Saved stereo calibration to {args.output}")
