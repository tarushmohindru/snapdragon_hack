import cv2
import numpy as np

from src.triangulation.triangulation import (
    build_projection_matrix,
    triangulate_point,
)


def test_triangulate_point_recovers_known_3d_point():
    true_point_3d = np.array([12.0, 5.0, 60.0])

    K = np.array([[600.0, 0.0, 320.0], [0.0, 600.0, 240.0], [0.0, 0.0, 1.0]])
    dist = np.zeros(5)

    R_A = np.eye(3)
    T_A = np.zeros(3)

    angle_y = np.deg2rad(5)
    R_B = np.array(
        [[np.cos(angle_y), 0, np.sin(angle_y)], [0, 1, 0], [-np.sin(angle_y), 0, np.cos(angle_y)]]
    )
    T_B = np.array([12.0, 0.0, 0.0])

    P_A = build_projection_matrix(K, R_A, T_A)
    P_B = build_projection_matrix(K, R_B, T_B)

    rvec_A, _ = cv2.Rodrigues(R_A)
    pt_A, _ = cv2.projectPoints(true_point_3d.reshape(1, 3), rvec_A, T_A, K, dist)
    pt_A = pt_A.reshape(2)

    rvec_B, _ = cv2.Rodrigues(R_B)
    pt_B, _ = cv2.projectPoints(true_point_3d.reshape(1, 3), rvec_B, T_B, K, dist)
    pt_B = pt_B.reshape(2)

    recovered = triangulate_point(pt_A, pt_B, P_A, P_B)
    error = np.linalg.norm(recovered - true_point_3d)

    assert error < 1e-3, f"Triangulation error too high: {error}"


if __name__ == "__main__":
    test_triangulate_point_recovers_known_3d_point()
    print("PASS - triangulation test")
