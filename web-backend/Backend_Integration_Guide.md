# FormFusion ML Pipeline — Backend Integration Guide

**For:** Fullstack teammate (FastAPI/WebSocket server)
**From:** ML track (calibration, triangulation, filtering, biomechanics)
**Purpose:** Everything you need to import and call the ML pipeline from your server. You don't need to understand the internals — just the function signatures and the data contract.

---

## 1. What Exists (File Structure)

```
02 Calibration/
    calibrate.py              -> single-camera intrinsic calibration
    stereo_calibrate.py       -> two-camera extrinsic calibration (R, T)
    generate_image.py         -> capture helper (not needed by you)
    capture_stereo_pairs.py   -> capture helper (not needed by you)

03 Triangulation/
    triangulation.py          -> DLT triangulation (2D + calibration -> 3D)

04 Biomechanics/
    one_euro_filter.py        -> smooths jittery 3D joint positions
    joint_angle.py            -> computes angle at a joint from 3 points
    rep_counting.py           -> counts reps from an angle signal

calib_deviceA.npz             -> saved intrinsics for phone A
calib_deviceB.npz             -> saved intrinsics for phone B
stereo_calibration_deviceA_deviceB.npz   -> saved R, T between phone A and B
```

All ML logic is plain Python + NumPy — no web framework dependency. You import these files directly into your server code.

---

## 2. Key Functions You'll Call

### From `triangulation.py`
```python
build_projection_matrix(K, R, T) -> 3x4 matrix
triangulate_point(point_2d_A, point_2d_B, P_A, P_B) -> (X, Y, Z)
```

### From `04 Biomechanics/one_euro_filter.py`
```python
OneEuroFilter3D(freq=30.0, min_cutoff=1.0, beta=0.1)
filter_instance.filter(point_3d, timestamp) -> smoothed (X, Y, Z)
```
**Important:** create ONE filter instance per joint, and reuse it across frames (don't recreate every frame — it needs memory of the previous value to work).

### From `04 Biomechanics/joint_angle.py`
```python
calculate_angle_3d(point_a, point_b, point_c) -> angle in degrees
```
`point_b` is the vertex (e.g. elbow); `point_a`/`point_c` are the adjacent joints (e.g. shoulder, wrist).

### From `04 Biomechanics/rep_counting.py`
```python
RepCounter(down_threshold, up_threshold, min_frames_per_state=3)
counter.update(angle) -> (rep_count, state)
```
**Important:** create ONE `RepCounter` instance per session/exercise, reuse across frames.

---

## 3. How to Wire It Together (server-side, per session)

**At session start (once, when calibration completes or is loaded):**
```python
import numpy as np
from triangulation import build_projection_matrix

calib_A = np.load("calib_deviceA.npz")
calib_B = np.load("calib_deviceB.npz")
stereo = np.load("stereo_calibration_deviceA_deviceB.npz")

P_A = build_projection_matrix(calib_A["camera_matrix"], np.eye(3), np.zeros(3))
P_B = build_projection_matrix(calib_B["camera_matrix"], stereo["R"], stereo["T"])

joint_filters = {}   # dict of OneEuroFilter3D, one per joint name, created lazily
rep_counter = RepCounter(down_threshold=160, up_threshold=50)  # tune per exercise
```

**Every frame (both phones' keypoints have arrived, roughly same timestamp):**
```python
def process_frame(keypoints_A: dict, keypoints_B: dict, timestamp: float) -> dict:
    joints_3d = {}
    for joint_name in keypoints_A:
        if joint_name not in keypoints_B:
            continue
        xA, yA, confA = keypoints_A[joint_name]
        xB, yB, confB = keypoints_B[joint_name]
        if confA < 0.5 or confB < 0.5:
            continue  # low-confidence detection, skip this joint this frame

        raw_point = triangulate_point((xA, yA), (xB, yB), P_A, P_B)

        if joint_name not in joint_filters:
            joint_filters[joint_name] = OneEuroFilter3D(freq=30.0, min_cutoff=1.0, beta=0.1)
        smoothed_point = joint_filters[joint_name].filter(raw_point, timestamp)

        joints_3d[joint_name] = smoothed_point.tolist()

    result = {"joints_3d": joints_3d}

    # Only if the needed joints were detected this frame:
    if all(j in joints_3d for j in ["left_shoulder", "left_elbow", "left_wrist"]):
        angle = calculate_angle_3d(joints_3d["left_shoulder"], joints_3d["left_elbow"], joints_3d["left_wrist"])
        rep_count, state = rep_counter.update(angle)
        result["elbow_angle"] = angle
        result["rep_count"] = rep_count
        result["state"] = state

    return result
```

Call this function inside your WebSocket message handler whenever you have a synced pair of keypoint messages from both phones.

---

## 4. API Contract (what the app sends/receives — for your reference, coordinate with app dev)

### Calibration phase

**App -> Server**, per captured frame:
```json
{
  "device_id": "phoneA",
  "frame": "<base64 image>",
  "mode": "intrinsic"
}
```

**Server -> App**, after each capture:
```json
{ "status": "captured", "pairs_so_far": 6, "pairs_needed": 15 }
```

**App -> Server**, when done capturing:
```json
{ "action": "finalize_calibration" }
```

**Server -> App**, final result:
```json
{ "status": "calibration_complete", "reprojection_error": 0.42, "quality": "good" }
```

### Live tracking phase

**App -> Server**, every frame, per phone:
```json
{
  "device_id": "phoneA",
  "timestamp": 1721234567.123,
  "keypoints": {
    "left_shoulder": {"x": 300, "y": 200, "confidence": 0.95},
    "left_elbow": {"x": 412, "y": 287, "confidence": 0.94},
    "left_wrist": {"x": 470, "y": 350, "confidence": 0.90}
  }
}
```

**Server -> App/Frontend**, after `process_frame()`:
```json
{
  "joints_3d": {"left_elbow": [12.1, 5.0, 60.2], "...": "..."},
  "elbow_angle": 143.2,
  "rep_count": 7,
  "state": "down"
}
```

---

## 5. Things to Know / Watch Out For

- **`process_frame()` needs BOTH phones' keypoints for the same moment before it can triangulate anything.** You'll need to buffer/sync incoming keypoint messages by timestamp (or just by "most recent from each phone" if exact sync isn't critical for the demo) before calling it.
- **Confidence filtering happens inside `process_frame()`** — if a joint's confidence is below 0.5 from either phone, that joint is skipped for that frame (not triangulated). Frontend should handle a joint being briefly absent from `joints_3d` gracefully (don't crash if a key is missing one frame).
- **Filters and the rep counter are STATEFUL** — they must persist across frames within a session (module-level dict / class instance tied to the session), not be recreated per request. If you're running multiple concurrent sessions (multiple users), keep separate filter/counter instances per session.
- **Calibration `.npz` files are loaded ONCE per session**, not per frame — they don't change unless a camera physically moves or a new calibration is run.
- **Current calibration `.npz` files in this repo are from testing/development, not a real two-phone hackathon setup yet.** Real calibration will be run at the venue; the loading code above will work identically once the real files replace these.
- **`down_threshold`/`up_threshold` in `RepCounter` and which joints feed `elbow_angle`** are currently placeholder values for a bicep-curl-style exercise — these need to be finalized once the actual demo exercise is decided.

---

## 6. Status Summary

| Component | Status |
|---|---|
| Intrinsic calibration | Tested, working on real webcam |
| Extrinsic/stereo calibration | Code works, not yet validated on real 2-phone hardware |
| Triangulation | Fully tested and verified (synthetic round-trip, zero error) |
| One-Euro filter | Fully tested and verified (noise/jitter reduction confirmed) |
| Joint angle calculation | Fully tested and verified (5/5 synthetic test cases pass) |
| Rep counting | Fully tested and verified (correct count + jitter-robustness confirmed) |
| Integration function (`process_frame`) | Provided above, not yet tested end-to-end with real pose data |

All four core ML modules (triangulation, filter, angle, rep counting) are independently tested and proven correct on synthetic data. What remains is (1) real two-device calibration and (2) an end-to-end test once real pose-estimation data is flowing from the app.
