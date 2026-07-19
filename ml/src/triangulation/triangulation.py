import numpy as np

def build_projection_matrix(K,R,T):

    RT = np.hstack((R,T.reshape(3,1)))
    P = K @ RT
    return P

def triangulate_point(point_2d_A, point_2d_B, P_A, P_B):

    x_A, y_A = point_2d_A
    x_B, y_B = point_2d_B
 
    A = np.zeros((4, 4))
    A[0] = x_A * P_A[2] - P_A[0]
    A[1] = y_A * P_A[2] - P_A[1]
    A[2] = x_B * P_B[2] - P_B[0]
    A[3] = y_B * P_B[2] - P_B[1]
 
    _, _, Vt = np.linalg.svd(A)
    X_homogeneous = Vt[-1]
 
    X = X_homogeneous[:3] / X_homogeneous[3]
    return X

def triangulate_multiview(points_2d, projection_matrices):
    
    assert len(points_2d) == len(projection_matrices)
    assert len(points_2d) >= 2
 
    rows = []
    for (x, y), P in zip(points_2d, projection_matrices):
        rows.append(x * P[2] - P[0])
        rows.append(y * P[2] - P[1])
 
    A = np.array(rows)
    _, _, Vt = np.linalg.svd(A)
    X_homogeneous = Vt[-1]
    X = X_homogeneous[:3] / X_homogeneous[3]
    return X



if __name__ == "__main__":
    import cv2

    true_point_3d = np.array([12.0, 5.0, 60.0])

    K = np.array([
        [600.0,   0.0, 320.0],
        [  0.0, 600.0, 240.0],
        [  0.0,   0.0,   1.0]
    ])
    dist = np.zeros(5)

    R_A = np.eye(3)
    T_A = np.zeros(3)

    angle_y = np.deg2rad(5)
    R_B = np.array([
        [ np.cos(angle_y), 0, np.sin(angle_y)],
        [ 0,                1, 0              ],
        [-np.sin(angle_y), 0, np.cos(angle_y)]
    ])
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

    print(f"True point:       {true_point_3d}")
    print(f"Triangulated:     {recovered}")
    print(f"Error:            {error:.6f}")
    print("PASS" if error < 1e-3 else "FAIL")