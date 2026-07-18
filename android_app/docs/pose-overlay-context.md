# Pose Overlay & Coordinate Pipeline — Context

Reference for the on-screen pose skeleton overlay and the coordinate-transformation work
done since the overlay feature started. Captures the architecture, the exact math, the bugs
fixed along the way, and what is still unverified.

---

## What the feature does

Draws a live skeleton (dots at joints + connecting lines) over the CameraX preview, tracking
detected people. Two-stage ML pipeline, both accelerated by the Qualcomm QNN HTP backend:

1. **RTMDet** (`rtmdet.tflite`, LiteRT + QNN delegate) → person bounding boxes.
2. **RTMPose** (`rtmpose_body2d.onnx` + `.data`, ONNX Runtime + QNN EP) → 133 COCO-WholeBody
   keypoints per detected person.

---

## Files

| File | Role |
|---|---|
| `pose/PoseAnalyzer.kt` | CameraX `ImageAnalysis.Analyzer`. Crops to `cropRect`, rotates, runs detector→estimator, reports `List<PersonPose>` + image dims. |
| `pose/RtmDetDetector.kt` | Detection. Letterbox to 640 (top-left anchored), run TFLite, filter class 0 + confidence, NMS, unscale boxes. |
| `pose/RtmPoseOnnxEstimator.kt` | Pose. mmpose top-down affine (center/scale → warp → SimCC decode → inverse affine). |
| `pose/PersonDetector.kt` | `PersonDetector` interface + `BoundingBox` + stub. |
| `pose/PoseEstimator.kt` | `PoseEstimator` interface + `PersonPose` + stub. `LandmarkPoint` lives in `PoseDetector.kt`. |
| `pose/Skeleton.kt` | Keypoint connectivity (`EDGES`) for body+feet (ids 0–22). Face/hands (23–132) are detected but not drawn. |
| `camera/PoseOverlay.kt` | Compose `Canvas` overlay. Scale-type-aware + front-camera mirroring. |
| `camera/CameraPreview.kt` | `PreviewView` wrapper. Defines `PREVIEW_SCALE_TYPE = FILL_CENTER` (single source of truth). |
| `viewmodel/CameraViewModel.kt` | Owns the pipeline + UI state (`lastPersons`, image dims, counts). `endSession()` dumps per-frame keypoints to Logcat. |
| `CameraScreen.kt` | Wires controller + analyzer + overlay. Derives `isFrontCamera` from the bound `cameraSelector`. |

---

## Coordinate pipeline (end to end)

Original camera frame → model coords → back to frame coords → screen coords.

**1. Frame acquisition (`PoseAnalyzer.analyze`)**
- `ImageProxy.toBitmap()` returns the **full, uncropped sensor buffer** — it ignores
  `imageProxy.cropRect`. `LifecycleCameraController` binds Preview + ImageAnalysis through a
  shared `ViewPort`, so `cropRect` is the region the preview actually shows. We crop to it
  manually (`cropToViewport`), else the analysis aspect ratio diverges from the preview.
- Then rotate by `imageInfo.rotationDegrees` so the bitmap is upright.

**2. Detection (`RtmDetDetector`)** — matches the Python reference exactly:
- `scale = 640 / max(w, h)`; resize and paste at **(0,0)** (top-left, not centered) onto a
  640×640 canvas.
- Unscale boxes back to frame pixels by dividing every coord by `scale` (no offset, since
  padding is top-left).

**3. Pose (`RtmPoseOnnxEstimator`)** — mmpose standard top-down affine:
- `computeCenterScale(box)`: center = box center; `w,h = boxSize × 1.25` (mmpose
  `GetBBoxCenterScale` padding); grow the shorter side to a clean **192:256** aspect ratio.
- `warpToModelInput`: affine-warp that `[center, scale]` region **straight out of the full
  frame** into 192×256 (black border fill for anything past the frame edge — no hard crop).
- Decode: `argmax(SimCC) / split_ratio(2.0)` → keypoint in 192×256 input space.
- `inputToOriginal`: exact inverse affine → `input/inputSize × scale + (center − scale/2)`
  → back to frame pixels.

**4. Screen mapping (`PoseOverlay`)**
- `FILL_CENTER` cover scale: `scale = max(viewW/imgW, viewH/imgH)`, centered offset. (FIT_*
  uses `min`.)
- `screenX = (mirror ? imgW − x : x) × scale + offsetX`, `screenY = y × scale + offsetY`.
- Mirror x only when the bound camera is front-facing. Canvas and Bitmap share top-left
  origin, so no origin correction.

---

## Model facts confirmed from `model.py` (qai_hub RTMPosebody2d)

- Input: **RGB, float [0,1], NCHW 1×3×256×192**. The exported `forward()` does RGB→BGR swap
  and `(x−mean)/std` **internally** → feed plain RGB/255, do **not** normalize in Kotlin.
- Output: `pred_x` [1,133,384], `pred_y` [1,133,512]; SimCC split ratio 2.0; 133 wholebody
  keypoints.

---

## Bugs found & fixed (chronological)

1. **Aspect-ratio distortion** in the old `fitBoxToAspectRatio` — it clamped each coordinate
   independently, skewing the box near frame edges. Caught by unit tests. (Later superseded
   entirely by the mmpose rewrite.)
2. **`cropRect` ignored** — `toBitmap()` returns the uncropped buffer; added `cropToViewport`.
   Verified against CameraX source (`ImageProxy.toBitmap` → `createBitmapFromImageProxy`, no
   crop applied).
3. **Overlay not scale-type / mirror aware** — made it FILL/FIT aware with a mirror param,
   keyed off the same `PREVIEW_SCALE_TYPE` constant the preview uses.
4. **Homemade crop ≠ mmpose** — replaced the ad-hoc (0.1 pad → hard crop → stretch) logic
   with the mmpose center/scale (1.25 padding) + affine-warp-from-full-frame + exact inverse
   decode. This was the core "joints don't track the body" fix.
5. **`MIN_VISIBILITY = 0.05` filter hid ALL joints** — the SimCC softmax-peakedness confidence
   over 384/512 bins is far below 0.05 for essentially every joint, so the gate removed
   everything. **Reverted** — the overlay now draws all body/feet keypoints unconditionally.

---

## Open / unverified (in priority order)

1. **`BBOX_PADDING = 1.25`** (`RtmPoseOnnxEstimator`) — the RTMPose config default, but not
   confirmed for this specific export. If the skeleton is correctly centered but uniformly
   too big/small, tune this.
2. **Normalization scale** — metadata says `[0,1]` and `model.py` supports that, but if the
   exported ONNX baked mean/std in 0–255 units, keypoints would be pure noise. If the
   skeleton is total garbage (not just mis-scaled), test feeding `[0,255]` (drop `/255f` in
   `bitmapToChwBuffer`).
3. **Not visually confirmed on-device** that joints track the body. Math is unit-verified
   (46 tests), but the live check is still pending.

---

## How to verify

- `./gradlew :app:testDebugUnitTest` — 46 pure-math tests (letterbox unscale, NMS/IoU,
  center/scale, inverse affine, SimCC decode).
- On device: open the camera screen, stand in frame, tap **End Session**, then
  `adb logcat -s CameraViewModel`. Each person prints `box=(...)` plus every keypoint as
  `id:x,y,confidence`. Keypoint `x,y` should fall **inside** that person's box and move as
  the person moves.
