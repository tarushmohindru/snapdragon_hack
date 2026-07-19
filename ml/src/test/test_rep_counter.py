import numpy as np

from src.biomechanics.rep_counting import RepCounter


def test_counts_correct_number_of_reps():
    n_reps = 5
    frames_per_rep = 30
    total_frames = n_reps * frames_per_rep

    t = np.linspace(0, n_reps * 2 * np.pi, total_frames)
    angle_signal = 105 + 65 * np.cos(t)

    np.random.seed(1)
    noisy_angle_signal = angle_signal + np.random.normal(0, 2.0, total_frames)

    counter = RepCounter(down_threshold=160, up_threshold=50, min_frames_per_state=3)
    for angle in noisy_angle_signal:
        rep_count, _state = counter.update(angle)

    assert rep_count == n_reps, f"Expected {n_reps} reps, got {rep_count}"


def test_jitter_near_threshold_does_not_cause_false_reps():
    np.random.seed(1)
    counter = RepCounter(down_threshold=160, up_threshold=50, min_frames_per_state=3)
    jittery_hold = 160 + np.random.normal(0, 5.0, 60)
    for angle in jittery_hold:
        rep_count, _state = counter.update(angle)

    assert rep_count == 0, f"Expected 0 false reps from jitter, got {rep_count}"


if __name__ == "__main__":
    test_counts_correct_number_of_reps()
    test_jitter_near_threshold_does_not_cause_false_reps()
    print("PASS - rep counter tests")
