# FormFusion Protocol v1

## Session flow

1. Host creates a session with `POST /api/v1/sessions`.
2. The backend returns `session_id`, an eight-character `join_code`, and a signed host token.
3. Each phone exchanges the join code and stable `device_id` at
   `POST /api/v1/sessions/{session_id}/join`.
4. Each phone opens `/api/v1/ws/sessions/{session_id}?token={device_token}` and sends
   `device.hello` as its first message.
5. Each phone uploads paired checkerboard images to
   `POST /api/v1/sessions/{session_id}/calibration/images`; the host calls
   `POST /api/v1/sessions/{session_id}/calibration/finalize`. For development, known 3x4
   projection matrices can be supplied directly at
   `PUT /api/v1/sessions/{session_id}/calibration/projections`.
6. Phones send `pose.frame`; the backend pairs frames, processes the pair, and broadcasts
   `pose.result` to all device/dashboard connections in the session.

## Required Android keypoint mapping

RTMPose COCO-WholeBody IDs are preserved. The default exercise currently uses:

| ID | Joint |
|---:|---|
| 5 | left shoulder |
| 7 | left elbow |
| 9 | left wrist |

Send only the body/foot points needed by the active exercise. Do not send all face and hand
points unless an exercise explicitly needs them.

## WebSocket messages

### Hello

```json
{
  "schema_version": 1,
  "type": "device.hello",
  "session_id": "8e6f...",
  "device_id": "phone-a",
  "role": "device"
}
```

Dashboard clients use a host token and set `role` to `dashboard` with `device_id: null`.

### Pose frame

```json
{
  "schema_version": 1,
  "type": "pose.frame",
  "session_id": "8e6f...",
  "device_id": "phone-a",
  "frame_id": 1042,
  "captured_at_ms": 1780000000123,
  "image": {
    "width": 1080,
    "height": 1920,
    "rotation_degrees": 0,
    "mirrored": false
  },
  "person": {
    "track_id": 1,
    "keypoints": [
      {"id": 5, "x": 300.1, "y": 200.2, "confidence": 0.95},
      {"id": 7, "x": 412.0, "y": 287.0, "confidence": 0.94},
      {"id": 9, "x": 470.0, "y": 350.0, "confidence": 0.90}
    ]
  }
}
```

Coordinates must be finite pixels in the upright analyzed bitmap coordinate system. Android
must include the same width/height used when mapping model output back to the frame.

### Result

```json
{
  "schema_version": 1,
  "type": "pose.result",
  "session_id": "8e6f...",
  "source_frame_ids": {"phone-a": 1042, "phone-b": 991},
  "captured_at_ms": 1780000000127,
  "joints_3d": {"5": [0.1, 0.2, 2.4]},
  "joint_angle_degrees": 143.2,
  "rep_count": 7,
  "state": "down",
  "pairing_delta_ms": 4,
  "reprojection_error": 0.42
}
```

Clients must tolerate joints being absent when either camera confidence is below the configured
threshold.

## Backpressure

- A session keeps a bounded queue per device.
- The nearest timestamps are paired within the configured tolerance.
- Duplicate, stale, and overflow frames are dropped rather than accumulating memory.
- `frame.ack` reports whether the submitted frame was queued or immediately paired.
- Android should initially send 10-15 pose frames per second and skip transmission when its own
  send queue is occupied.

## Error shape

HTTP errors:

```json
{"error": {"code": "invalid_join_code", "message": "join code is invalid"}}
```

WebSocket errors:

```json
{
  "schema_version": 1,
  "type": "error",
  "code": "calibration_required",
  "message": "projection calibration has not been configured",
  "request_id": null
}
```
