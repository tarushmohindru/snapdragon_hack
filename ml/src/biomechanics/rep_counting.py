import numpy as np

class RepCounter:
 
    def __init__(self, down_threshold, up_threshold, min_frames_per_state=3):

        self.down_threshold = down_threshold
        self.up_threshold = up_threshold
        self.min_frames_per_state = min_frames_per_state
 
        self.state = "down"   
        self.rep_count = 0
        self._candidate_state = None
        self._candidate_count = 0
 
    def update(self, angle):
        
        
        if angle >= self.down_threshold:
            frame_vote = "down"
        elif angle <= self.up_threshold:
            frame_vote = "up"
        else:
            frame_vote = None  
 
        if frame_vote is None:
            self._candidate_count = 0
            self._candidate_state = None
            return self.rep_count, self.state
 
        if frame_vote == self._candidate_state:
            self._candidate_count += 1
        else:
            self._candidate_state = frame_vote
            self._candidate_count = 1
 
        if self._candidate_count >= self.min_frames_per_state and frame_vote != self.state:
            
            if self.state == "up" and frame_vote == "down":
                self.rep_count += 1
            self.state = frame_vote
            self._candidate_count = 0
 
        return self.rep_count, self.state
    
if __name__ == "__main__":
    print("=== Rep counter self-test ===\n")
 
    n_reps = 5
    frames_per_rep = 30
    total_frames = n_reps * frames_per_rep
 
    t = np.linspace(0, n_reps * 2 * np.pi, total_frames)

    angle_signal = 105 + 65 * np.cos(t)  
 
    np.random.seed(1)
    noisy_angle_signal = angle_signal + np.random.normal(0, 2.0, total_frames)
 
    counter = RepCounter(down_threshold=160, up_threshold=50, min_frames_per_state=3)
 
    for angle in noisy_angle_signal:
        rep_count, state = counter.update(angle)
 
    print(f"Simulated reps: {n_reps}")
    print(f"Counted reps:   {rep_count}")
 
    if rep_count == n_reps:
        print("\nPASS - rep counter correctly counted the simulated reps.")
    else:
        print(f"\nFAIL - expected {n_reps}, got {rep_count}. Check thresholds or debounce.")
 
    
    print("\n--- Jitter robustness test ---")
    print("Holding angle near the 'down' threshold with noise, expecting 0 reps counted...")
    counter2 = RepCounter(down_threshold=160, up_threshold=50, min_frames_per_state=3)
    jittery_hold = 160 + np.random.normal(0, 5.0, 60) 
    for angle in jittery_hold:
        rc, st = counter2.update(angle)
    print(f"Reps counted while just holding near threshold: {rc}")
    print("PASS - no false reps from jitter" if rc == 0 else "FAIL - jitter caused false rep counts")


