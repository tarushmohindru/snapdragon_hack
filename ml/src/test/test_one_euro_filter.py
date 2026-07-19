import numpy as np
from src.biomechanics.one_euro_filter import OneEuroFilter


def test_filter_reduces_noise_and_jitter():
    np.random.seed(42)
    freq = 30.0
    duration_s = 3.0
    n_samples = int(freq * duration_s)
    t = np.linspace(0, duration_s, n_samples)

    true_signal = 10 * np.sin(2 * np.pi * 0.5 * t)
    noise = np.random.normal(0, 1.0, n_samples)
    noisy_signal = true_signal + noise

    f = OneEuroFilter(freq=freq, min_cutoff=1.0, beta=0.1)
    filtered_signal = np.array([f.filter(val, timestamp=ti) for val, ti in zip(noisy_signal, t)])

    rms_noisy = np.sqrt(np.mean((noisy_signal - true_signal) ** 2))
    rms_filtered = np.sqrt(np.mean((filtered_signal - true_signal) ** 2))

    jitter_noisy = np.mean(np.abs(np.diff(noisy_signal)))
    jitter_filtered = np.mean(np.abs(np.diff(filtered_signal)))

    assert rms_filtered < rms_noisy, "Filter did not reduce error vs true signal"
    assert jitter_filtered < jitter_noisy, "Filter did not reduce frame-to-frame jitter"


if __name__ == "__main__":
    test_filter_reduces_noise_and_jitter()
    print("PASS - one-euro filter test")
