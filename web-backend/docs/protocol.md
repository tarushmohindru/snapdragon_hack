# FormFusion protocol v1

## Session flow

1. Create a session with `POST /api/v1/sessions`.
2. Each phone joins with the returned `session_id` and `join_code`, plus its stable `device_id`.
3. Both phones upload matching checkerboard capture IDs and the host finalizes calibration.
4. Each phone opens `/api/v1/ws/sessions/{session_id}` and sends `device.hello` first.
5. Phones send `pose.frame`; the backend pairs timestamps and forwards the observations to ML.
6. ML returns the canonical result. The backend persists and broadcasts it unchanged.

No bearer or WebSocket token is required in the current development configuration.

## Phone hello

```json
{
  "schema_version": 1,
  "type": "device.hello",
  "session_id": "8e6f...",
  "device_id": "android-a1b2c3",
  "role": "device"
}
```

A dashboard uses `"role": "dashboard"` and omits `device_id`.

## Pose frame

```json
{
  "schema_version": 1,
  "type": "pose.frame",
  "session_id": "8e6f...",
  "device_id": "android-a1b2c3",
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

Coordinates are finite pixels in the upright analyzed bitmap coordinate system. Preserve the
RTMPose keypoint IDs and send the exact image dimensions used to map model output.

## Canonical result

```json
{
  "schema_version": 1,
  "type": "pose.result",
  "session_id": "8e6f...",
  "captured_at_ms": 1780000000127,
  "joints_3d": {
    "7": {"x": 0.2, "y": 0.8, "z": 2.4, "confidence": 0.94, "observations": 2}
  },
  "angles": [
    {"name": "left_elbow", "degrees": 143.2, "joint_ids": [5, 7, 9]}
  ],
  "primary_angle_degrees": 143.2,
  "rep_count": 7,
  "movement_state": "extended",
  "form_quality": "good",
  "metadata": {
    "coordinate_system": "camera_a_world_right_handed",
    "units": "centimeters",
    "calibration_id": "cal-123",
    "reprojection_error": 0.42,
    "source_frame_ids": {"android-a1b2c3": 1042, "android-d4e5f6": 991},
    "source_timestamps_ms": {"android-a1b2c3": 1780000000123, "android-d4e5f6": 1780000000127},
    "pairing_delta_ms": 4,
    "processing_time_ms": 2.7
  }
}
```

Clients must tolerate absent joints when a landmark is missing or below the ML confidence limit.

## Backpressure and errors

- Android targets 10–15 frames per second and uses CameraX `KEEP_ONLY_LATEST`.
- The backend keeps a bounded queue per device and pairs nearest timestamps within tolerance.
- Duplicate, stale, and overflow frames are dropped rather than accumulating memory.
- `frame.ack` reports `queued`, `paired`, or `dropped`.
- HTTP errors use `{"error":{"code":"...","message":"..."}}`.
- WebSocket errors use the `error` message contract and close policy-invalid connections.
