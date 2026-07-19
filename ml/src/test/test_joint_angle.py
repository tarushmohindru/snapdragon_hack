import numpy as np
from src.biomechanics.joint_angle import calculate_angle_3d


def test_straight_arm_is_180_degrees():
    angle = calculate_angle_3d((0, 0, 0), (1, 0, 0), (2, 0, 0))
    assert abs(angle - 180.0) < 0.01


def test_right_angle_is_90_degrees():
    angle = calculate_angle_3d((0, 1, 0), (0, 0, 0), (1, 0, 0))
    assert abs(angle - 90.0) < 0.01


def test_fully_folded_is_0_degrees():
    angle = calculate_angle_3d((1, 0, 0), (0, 0, 0), (1, 0, 0))
    assert abs(angle - 0.0) < 0.01


def test_45_degree_bend_in_3d():
    c = (np.cos(np.radians(45)), np.sin(np.radians(45)), 0)
    angle = calculate_angle_3d((1, 0, 0), (0, 0, 0), c)
    assert abs(angle - 45.0) < 0.01


if __name__ == "__main__":
    test_straight_arm_is_180_degrees()
    test_right_angle_is_90_degrees()
    test_fully_folded_is_0_degrees()
    test_45_degree_bend_in_3d()
    print("PASS - joint angle tests")
