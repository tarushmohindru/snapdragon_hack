# Context: ChArUco Board as a Calibration Substitute

## What was discussed

**Question raised:** is there a substitute for the checkerboard-based calibration approach, given the ongoing difficulty getting reliable stereo/extrinsic calibration results (stuck around 4.7-4.8 reprojection error across multiple attempts, root-caused to insufficient pair count)?

## Three alternatives presented

1. **ArUco/ChArUco single-shot pose (recommended)** — replaces the *extrinsic* calibration step only (not intrinsics). Instead of capturing 15-20 matched stereo pairs and running `cv2.stereoCalibrate`, each camera captures ONE view of a ChArUco board and uses `cv2.solvePnP()` (or the newer `estimatePoseCharucoBoard`) to directly compute "where is this camera relative to the board." Both cameras' poses relative to the same board are then combined to get their relative position to each other. Faster and more robust to partial occlusion/tilt than the multi-pair stereo approach.

2. **Approximate/default intrinsics** — emergency-only fallback. Skip intrinsic calibration entirely, assume `fx ≈ fy ≈ image_width`, `cx, cy` = image center. Degraded accuracy, unverified — only suggested as a last resort if genuinely out of time.

3. **ARCore-based pose tracking** — the "real" product-grade substitute. Android's ARCore already does visual-inertial camera pose tracking natively; if the app integrates it, each phone reports its own 6DOF pose with no checkerboard needed at all. Flagged as real app-dev integration work, not a quick swap given the remaining timeline.

## Decision

Recommended path: **keep the existing checkerboard-based intrinsic calibration** (already proven, 0.0767 reprojection error) but **replace the extrinsic/stereo step** with a ChArUco-based single-shot `solvePnP` approach, since that directly targets the specific failure mode that's been causing problems (insufficient pair counts, stereo reprojection error stuck high).

## What was built

**`generate_charuco.py`** — generates a print-ready ChArUco board image.

Board specification (must be reused consistently in any detection/pose code):
```
SQUARES_X = 5
SQUARES_Y = 7
SQUARE_LENGTH_CM = 3.0   (measure the actual printed square and correct if needed)
MARKER_LENGTH_CM = 2.2
DICT_NAME = cv2.aruco.DICT_5X5_100
```

**`charuco_board.png`** — the rendered board image (1890x2598 px, ~300 DPI, with a white margin for handling/printing). Print at actual size — do not let the printer auto-scale ("fit to page"), since that would break the SQUARE_LENGTH_CM assumption.

**Verification performed (not just generated blind):** the board image was tested against OpenCV's own `CharucoDetector` before being handed over — successfully detected 17 ArUco markers and 24 ChArUco corners on a flat test render, confirming the board itself is valid and usable, not just visually plausible.

## Why this matters for the project

This board is specifically more tolerant of the two failure modes that have repeatedly caused problems in stereo calibration attempts so far: partial occlusion (each marker is independently identifiable, unlike a plain checkerboard which needs the *entire* pattern visible) and steep viewing angles/tilt. This should make the onsite/real-hardware calibration step meaningfully more reliable than the plain-checkerboard `stereoCalibrate` approach that has been tested repeatedly without a trustworthy result.

## What's NOT done yet

The actual `solvePnP`/`estimatePoseCharucoBoard`-based extrinsic calibration script (the replacement for `calibrate_stereo.py`) has been proposed and discussed but **not yet written**. This was offered as the immediate next step at the end of the conversation, not yet completed.

## Files produced

- `generate_charuco.py` — board image generator (verified working)
- `charuco_board.png` — the print-ready board image itself (verified detectable)
