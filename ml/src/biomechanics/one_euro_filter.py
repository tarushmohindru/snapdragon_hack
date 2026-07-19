import numpy as np
import math

class LowPassFilter:
    """Simple exponential low-pass filter, the building block for One-Euro."""
 
    def __init__(self):
        self.initialized = False
        self.x_prev = None
 
    def filter(self, x, alpha):
        if not self.initialized:
            self.x_prev = x
            self.initialized = True
            return x
        x_filtered = alpha * x + (1 - alpha) * self.x_prev
        self.x_prev = x_filtered
        return x_filtered

class OneEuroFilter:
 
    def __init__(self, freq=30.0, min_cutoff=1.0, beta=0.0, d_cutoff=1.0):
        self.freq = freq
        self.min_cutoff = min_cutoff
        self.beta = beta
        self.d_cutoff = d_cutoff
 
        self.x_filter = LowPassFilter()
        self.dx_filter = LowPassFilter()
 
        self.t_prev = None
 
    def _alpha(self, cutoff, dt):
        tau = 1.0 / (2 * math.pi * cutoff)
        return 1.0 / (1.0 + tau / dt)
 
    def filter(self, x, timestamp=None):
        if self.t_prev is None:
            dt = 1.0 / self.freq
        else:
            dt = timestamp - self.t_prev if timestamp is not None else 1.0 / self.freq
            if dt <= 0:
                dt = 1.0 / self.freq
        self.t_prev = timestamp
 
        # Estimate the derivative (speed of change)
        if self.x_filter.x_prev is None:
            dx = 0.0
        else:
            dx = (x - self.x_filter.x_prev) / dt
 
        dx_alpha = self._alpha(self.d_cutoff, dt)
        dx_filtered = self.dx_filter.filter(dx, dx_alpha)
 
        # Adaptive cutoff: higher speed -> higher cutoff -> less smoothing (less lag)
        cutoff = self.min_cutoff + self.beta * abs(dx_filtered)
        x_alpha = self._alpha(cutoff, dt)
        x_filtered = self.x_filter.filter(x, x_alpha)
 
        return x_filtered

class OneEuroFilter3D:
    """
    Convenience wrapper: applies an independent OneEuroFilter to each of
    X, Y, Z so you can filter a full 3D joint position in one call.
    """
 
    def __init__(self, freq=30.0, min_cutoff=1.0, beta=0.0, d_cutoff=1.0):
        self.fx = OneEuroFilter(freq, min_cutoff, beta, d_cutoff)
        self.fy = OneEuroFilter(freq, min_cutoff, beta, d_cutoff)
        self.fz = OneEuroFilter(freq, min_cutoff, beta, d_cutoff)
 
    def filter(self, point_3d, timestamp=None):
        x, y, z = point_3d
        return np.array([
            self.fx.filter(x, timestamp),
            self.fy.filter(y, timestamp),
            self.fz.filter(z, timestamp),
        ])
 
 #depthmap
 #tpose
 