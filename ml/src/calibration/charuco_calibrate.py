from pathlib import Path
from typing import TypeAlias

import cv2
import numpy as np


SQUARES_X = 5
SQUARES_Y = 7
SQUARE_LENGTH_CM = 3.0
MARKER_LENGTH_CM = 2.2
CameraPose: TypeAlias = tuple[np.ndarray, np.ndarray, np.ndarray, int]


def _camera_matrix(image: np.ndarray) -> np.ndarray:
    height, width = image.shape[:2]
    focal = float(max(width, height))
    return np.asarray(
        [[focal, 0.0, width / 2.0], [0.0, focal, height / 2.0], [0.0, 0.0, 1.0]],
        dtype=np.float64,
    )


def _pose(image: np.ndarray) -> CameraPose | None:
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_5X5_100)
    board = cv2.aruco.CharucoBoard(
        (SQUARES_X, SQUARES_Y), SQUARE_LENGTH_CM, MARKER_LENGTH_CM, dictionary
    )
    detector = cv2.aruco.CharucoDetector(board)
    corners, ids, _, _ = detector.detectBoard(image)
    if ids is None or corners is None or len(ids) < 6:
        return None
    matrix = _camera_matrix(image)
    distortion = np.zeros(5, dtype=np.float64)
    object_points = board.getChessboardCorners()[ids.reshape(-1)].astype(np.float32)
    image_points = corners.reshape(-1, 2).astype(np.float32)
    solved, rotation_vector, translation = cv2.solvePnP(
        object_points,
        image_points,
        matrix,
        distortion,
        flags=cv2.SOLVEPNP_ITERATIVE,
    )
    if not solved:
        return None
    rotation, _ = cv2.Rodrigues(rotation_vector)
    return matrix, rotation, translation.reshape(3), len(ids)


def calibrate_charuco_pair(
    paired_paths: list[tuple[Path, Path]],
) -> dict[str, np.ndarray | float | int] | None:
    """Use the matching pair with the most jointly detected ChArUco corners."""
    best: tuple[int, CameraPose, CameraPose] | None = None
    for path_a, path_b in paired_paths:
        image_a = cv2.imread(str(path_a))
        image_b = cv2.imread(str(path_b))
        if image_a is None or image_b is None:
            continue
        pose_a = _pose(image_a)
        pose_b = _pose(image_b)
        if pose_a is None or pose_b is None:
            continue
        score = min(pose_a[3], pose_b[3])
        if best is None or score > best[0]:
            best = (score, pose_a, pose_b)
    if best is None:
        return None
    score, pose_a, pose_b = best
    matrix_a, rotation_a, translation_a, _ = pose_a
    matrix_b, rotation_b, translation_b, _ = pose_b
    rotation_a_to_b = rotation_b @ rotation_a.T
    translation_a_to_b = translation_b - rotation_a_to_b @ translation_a
    return {
        "camera_matrix_a": matrix_a,
        "distortion_a": np.zeros(5),
        "camera_matrix_b": matrix_b,
        "distortion_b": np.zeros(5),
        "rotation_a_to_b": rotation_a_to_b,
        "translation_a_to_b": translation_a_to_b,
        "detected_corners": score,
    }
