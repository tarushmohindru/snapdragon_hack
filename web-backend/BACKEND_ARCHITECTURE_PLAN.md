# FormFusion Backend Architecture and Delivery Plan

Status: proposed implementation plan  
Scope: backend first; Android and web frontend integration boundaries are included, but their implementation follows the backend contract.

## 1. Decision

Build the backend with **Python 3.12 + FastAPI**, not Node.js.

The calibration, triangulation, One-Euro filtering, joint-angle, and rep-counting pipeline is specified as Python + NumPy. Keeping the API and pipeline in one Python process avoids:

- a Node-to-Python subprocess/RPC bridge;
- duplicated schemas and error handling across two services;
- copying frame data between runtimes;
- extra deployment and observability complexity.

FastAPI supplies HTTP APIs, WebSockets, dependency injection, OpenAPI generation, lifecycle hooks, and typed request validation. CPU-heavy calibration work will not run on the async event loop.

## 2. Current Repository Reality

- Android already runs RTMDet + RTMPose on-device and produces 133 keypoints per detected person.
- The Android host/follower QR flow currently connects phones directly using `Java-WebSocket`.
- Landmark streaming exists only as a placeholder (`sendLandmarks`); camera results are currently retained in memory and dumped to Logcat.
- `web-backend/` has an integration guide but no server implementation.
- `ml/` is currently a placeholder in this checkout. The Python modules named by the integration guide are missing and must be added or recovered before real pipeline integration.
- Calibration files in the guide are development artifacts and must be replaceable per device pair/session.

## 3. System Boundary

```text
Android phone A --\
                   \-- secure WebSocket --> FastAPI session pipeline --> Web dashboard
Android phone B --/                            |
                                                +--> optional session persistence

HTTP API: session creation/join, calibration upload/finalize, metadata, health
WebSocket: live keypoint frames, status events, processed 3D pose and rep metrics
```

The backend becomes the session authority. The existing QR user experience can remain, but the QR should contain a backend session ID, short-lived join token, and backend URL rather than a host-phone IP and port.

## 4. Design Principles

- **Single responsibility:** transport, session state, synchronization, calibration, and biomechanics remain separate modules.
- **Dependency inversion:** API handlers depend on service/repository protocols, not concrete in-memory or Redis implementations.
- **Typed boundaries:** all inbound/outbound messages use versioned Pydantic models; unknown message types and invalid ranges are rejected.
- **Bounded resources:** bounded queues/deques, payload-size limits, connection limits, stale-frame eviction, and timeouts prevent memory growth.
- **Async for I/O, workers for CPU:** sockets and persistence are async; OpenCV calibration/finalization runs outside the event loop.
- **No live image streaming:** phones send keypoints for tracking. Images are uploaded only when calibration explicitly requires them.
- **Fail explicitly:** structured error codes, correlation IDs, readiness checks, and no silent exception swallowing.
- **Library first:** use maintained libraries for validation, settings, serialization, logging, metrics, numerical work, calibration, and testing. Custom code is limited to FormFusion domain logic.

## 5. Proposed Backend Layout

```text
web-backend/
  pyproject.toml
  uv.lock
  .env.example
  Dockerfile
  README.md
  docs/
    protocol.md
    operations.md
    adr/
      0001-fastapi-over-node.md
      0002-session-state-and-scaling.md
  src/formfusion/
    main.py
    config.py
    logging.py
    api/
      dependencies.py
      errors.py
      routes/
        health.py
        sessions.py
        calibration.py
      websocket.py
    contracts/
      common.py
      inbound.py
      outbound.py
    domain/
      models.py
      exercises.py
      errors.py
    services/
      session_service.py
      frame_synchronizer.py
      calibration_service.py
      pipeline_service.py
      broadcaster.py
    pipeline/
      triangulation.py
      one_euro_filter.py
      joint_angle.py
      rep_counting.py
      formfusion_pipeline.py
    repositories/
      protocols.py
      memory.py
      redis.py
  tests/
    unit/
    integration/
    contract/
    fixtures/
```

## 6. API and WebSocket Contract

All messages include `schema_version`, `type`, `session_id`, and `request_id` or `frame_id` as appropriate.

### HTTP control plane

- `POST /api/v1/sessions` - create a session and host credentials.
- `POST /api/v1/sessions/{session_id}/join` - exchange a join code/token for device credentials.
- `GET /api/v1/sessions/{session_id}` - session/device/calibration status.
- `POST /api/v1/sessions/{session_id}/calibration/images` - multipart image upload with device and capture metadata.
- `POST /api/v1/sessions/{session_id}/calibration/finalize` - start calibration finalization.
- `GET /api/v1/sessions/{session_id}/results` - latest result/session summary.
- `DELETE /api/v1/sessions/{session_id}` - close a session and release state.
- `GET /health/live` and `GET /health/ready` - process and dependency health.

### WebSocket data plane

- `/api/v1/ws/sessions/{session_id}`
- Authenticate during connection using a short-lived signed token.
- First client message is `device.hello`, declaring `device_id`, role, camera metadata, and supported schema version.
- Android sends `pose.frame` messages.
- Backend emits `session.status`, `frame.ack`, `pose.result`, `calibration.status`, and structured `error` messages.

Recommended live payload:

```json
{
  "schema_version": 1,
  "type": "pose.frame",
  "session_id": "019...",
  "device_id": "phone-a",
  "frame_id": 1042,
  "captured_at_ms": 1780000000123,
  "image": {"width": 1080, "height": 1920, "rotation_degrees": 0, "mirrored": false},
  "person": {
    "track_id": 1,
    "keypoints": [
      {"id": 5, "x": 300.1, "y": 200.2, "confidence": 0.95}
    ]
  }
}
```

Send only the body keypoints needed by the selected exercise when possible (typically 17 or 23), not all 133 face/hand points. This materially reduces bandwidth, parsing, and allocation pressure.

## 7. Frame Synchronization and Backpressure

Each active session owns a `FrameSynchronizer` with one bounded queue per device.

- Queue capacity: configurable, initially 8-16 frames per device.
- Pairing: nearest monotonic/capture timestamp within a configurable tolerance, initially 40-80 ms.
- Eviction: discard frames older than the newest peer frame minus the tolerance window.
- Backpressure: when a queue is full, drop the oldest unpaired frame and increment a metric; never allow an unbounded buffer.
- Duplicate/out-of-order handling: reject duplicate `frame_id`; tolerate bounded reordering.
- Rate limit: initially target 10-15 transmitted pose frames/second/device even if on-device inference runs faster.
- Acknowledgment: optionally report dropped/processed frame IDs so Android can adapt its send rate.

This is a small amount of necessary custom domain logic. Serialization, validation, WebSocket handling, and numerical operations remain library-backed.

## 8. Stateful Pipeline Lifecycle

One `FormFusionPipeline` instance exists per active session and owns:

- projection matrices loaded once after calibration;
- one `OneEuroFilter3D` instance per joint;
- one or more exercise-specific `RepCounter` instances;
- latest 3D pose and metrics;
- bounded session summary data.

It must not be recreated per frame. Session close, timeout, or server shutdown releases it explicitly.

Exercise behavior should be configuration-driven (`ExerciseDefinition`) instead of hard-coded bicep thresholds. A definition names required joints, angle triplets, thresholds, minimum state duration, and confidence cutoff.

## 9. CPU and Memory Policy

- On-device inference remains on the Snapdragon/QNN path; the server does not rerun pose models.
- Triangulation/filtering/angle updates are small NumPy operations and may run inline initially, with latency metrics proving whether offloading is needed.
- OpenCV intrinsic/stereo calibration is CPU-heavy and runs via a bounded worker executor, never directly in an async route/WebSocket handler.
- Calibration images use multipart uploads and streamed file handling, not base64 JSON.
- Enforce maximum upload size, image dimensions, frame message size, keypoint count, active sessions, devices per session, and session duration.
- Do not retain every raw frame. Keep counters, the latest result, and an optional bounded/downsampled history for the dashboard.

## 10. Persistence and Scaling

### First reliable release

Run one application process with an in-memory repository. This is the safest topology for stateful per-session filters/counters and a hackathon deployment. Put it behind a reverse proxy/load balancer that handles TLS.

### Production scale-out

Introduce Redis only through repository/broker interfaces:

- session metadata, token revocation, presence, and TTLs in Redis;
- pub/sub or streams for dashboard fan-out and cross-instance events;
- session-affinity routing so both phones in a session reach the same owning worker;
- explicit ownership/lease for each live pipeline.

Do not simply add multiple workers while keeping session state in process memory; paired frames could land in different workers and break synchronization.

Durable SQL storage is optional for users and historical summaries, not required in the live-frame hot path.

## 11. Security Baseline

- TLS only in deployed environments (`https`/`wss`).
- Cryptographically random session IDs and short-lived signed role/device tokens.
- Never place long-lived secrets in QR codes.
- Validate origin for browser dashboard connections and configure explicit CORS origins.
- Rate-limit session creation, joins, uploads, and WebSocket messages.
- Reject oversized, malformed, non-finite, or out-of-frame coordinates.
- Redact tokens and image content from logs.
- Calibration images expire automatically and are not persisted by default.
- Dependency pinning and automated vulnerability scanning in CI.

## 12. Libraries

Initial dependency set, with exact compatible versions locked by `uv` during implementation:

- `fastapi` and `uvicorn[standard]` - HTTP/WebSocket ASGI application and server.
- `pydantic` and `pydantic-settings` - contracts and environment configuration.
- `numpy` - vector/matrix operations.
- `opencv-python-headless` - calibration and image decoding without GUI dependencies.
- `PyJWT` - signed short-lived session/device tokens.
- `structlog` - structured logs.
- `prometheus-fastapi-instrumentator` - HTTP metrics; custom counters/histograms for WebSockets and frame sync.
- `httpx`, `pytest`, `pytest-asyncio`, and `hypothesis` - API, async, and property-based tests.
- `ruff` and `mypy` - formatting/linting and static type checks.
- `redis` - added only when the Redis repository/broker is implemented.

Avoid introducing a task queue until calibration load or reliability data proves one is needed. A bounded executor is sufficient for the first version.

## 13. Observability

Structured logs include `request_id`, `session_id`, `device_id`, `frame_id`, event type, and latency, while excluding tokens and raw image bodies.

Required metrics:

- active sessions and WebSocket connections;
- inbound frames, paired frames, dropped frames, and validation failures;
- pairing time delta distribution;
- pipeline processing latency and end-to-end result latency;
- calibration duration/failure/reprojection error;
- queue depth and event-loop lag;
- reconnects and connection duration.

Readiness fails if required calibration assets, configuration, or external dependencies are unavailable. Liveness only indicates that the process/event loop is alive.

## 14. Testing Strategy

- Unit tests for triangulation, filters, angles, rep state machines, synchronizer edge cases, token handling, and message validation.
- Property tests for timestamp ordering, queue bounds, non-finite coordinates, and angle invariants.
- Contract fixtures shared with Android/frontend for every message type.
- WebSocket integration tests covering connect/auth/hello/frame pairing/result/disconnect/reconnect.
- Calibration integration tests with known fixture images and accepted reprojection-error bounds.
- Load test with two simulated phones per session, including slow clients and bursty/out-of-order frames.
- End-to-end hardware test with two real Android devices before frontend polish.

## 15. Delivery Phases

### Phase 0 - unblock inputs

1. Recover/add the Python ML modules and calibration fixtures named in `Backend_Integration_Guide.md`.
2. Confirm the demo exercise and its joint IDs/thresholds.
3. Confirm whether phone timestamps are wall-clock, monotonic, or both. Prefer a monotonic capture timestamp plus server receive time.

### Phase 1 - backend foundation

1. Scaffold the package, locked dependencies, settings, logging, error model, health endpoints, Dockerfile, and CI checks.
2. Define all Pydantic HTTP/WebSocket contracts and protocol documentation.
3. Implement secure session creation/join and an in-memory repository with TTL cleanup.

### Phase 2 - live transport

1. Implement authenticated WebSocket connections and connection registry.
2. Implement bounded frame synchronization, backpressure, status events, and dashboard fan-out.
3. Add WebSocket contract/integration tests and a two-device simulator.

### Phase 3 - ML/biomechanics integration

1. Wrap the recovered modules in `FormFusionPipeline`.
2. Load calibration once, keep filters/counters per session, and emit typed results.
3. Add golden tests using synthetic and recorded real keypoints.

### Phase 4 - calibration

1. Add multipart capture upload and metadata validation.
2. Run finalize work in a bounded worker executor.
3. Store calibration results by device pair and report quality/reprojection error.

### Phase 5 - Android integration

1. Replace host-phone socket authority with backend session/join credentials while retaining QR UX.
2. Add a bounded/rate-limited landmark sender outside the camera analysis hot path.
3. Send selected keypoints, timestamps, dimensions, rotation, mirror state, and frame IDs.
4. Add reconnect, connection quality, calibration progress, and server error UI.

### Phase 6 - web frontend

1. Generate or share TypeScript contracts from the backend OpenAPI/JSON Schemas.
2. Build session join/status, live 3D skeleton, rep/angle cards, quality indicators, and summary views.
3. Treat missing joints and temporary disconnects as normal states.

### Phase 7 - hardening and deployment

1. Load/failure tests, TLS proxy, explicit CORS/origin rules, rate limits, metrics, and alerting.
2. Produce runbooks for local development, deployment, calibration, backup demo, and incident recovery.
3. Add Redis/session affinity only if multi-process or multi-instance deployment is required.

## 16. Definition of Done for Backend v1

- Two simulated and then two real phones can securely join one backend session.
- Invalid or oversized messages are rejected without crashing or growing memory.
- Frames are paired within the configured tolerance; stale frames are predictably dropped.
- One stateful pipeline per session emits 3D joints, angle, rep count, state, confidence/quality, and latency.
- A dashboard client receives the same live results without polling.
- Calibration finalization cannot block WebSocket heartbeats/live sessions.
- Unit, contract, integration, and basic load tests pass in CI.
- Docker deployment exposes liveness/readiness and useful structured logs/metrics.
- Protocol and operations documentation match the implementation.

## 17. Immediate Implementation Order

Do not begin with UI or database work. Implement in this order:

1. package/tooling/configuration;
2. versioned contracts;
3. sessions and authentication;
4. WebSocket connection lifecycle;
5. bounded frame synchronizer plus simulator;
6. pipeline adapter and tests;
7. calibration worker path;
8. Android sender integration;
9. dashboard frontend;
10. deployment hardening.

