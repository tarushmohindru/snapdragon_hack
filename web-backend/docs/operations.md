# Backend operations

The backend keeps only ephemeral frame-pairing queues in memory. ML owns calibration,
triangulation, filtering, biomechanics, reps, and LLM processing. SQLite owns durable sessions,
devices, results, and summaries.

## Environment

- `FORMFUSION_ENVIRONMENT`: `development`, `test`, or `production`.
- `FORMFUSION_ALLOWED_ORIGINS`: JSON array of dashboard origins.
- `FORMFUSION_SESSION_TTL_SECONDS`: live session lifetime.
- `FORMFUSION_FRAME_QUEUE_CAPACITY`: bounded frame queue per device.
- `FORMFUSION_FRAME_SYNC_TOLERANCE_MS`: maximum camera timestamp difference.
- `FORMFUSION_ML_SERVICE_URL`: internal ML base URL.
- `FORMFUSION_ML_SERVICE_KEY`: backend-to-ML service key; 32+ characters in production.
- `FORMFUSION_JOIN_CODE_SECRET`: HMAC key used to store join codes safely.
- `FORMFUSION_DATABASE_PATH`: SQLite path on persistent storage.

## Health and scaling

- `/health/live` checks the backend process.
- `/health/ready` also verifies ML readiness.
- `/metrics` exposes WebSocket/frame/pairing/processing Prometheus metrics.

Use one backend worker for local deployment. Multiple replicas require session affinity for
in-memory pairing state, shared database storage, and a shared event broker for WebSocket/SSE
fan-out. Terminate TLS at a reverse proxy and forward WebSocket upgrades.

Authentication is intentionally absent for now. Keep development deployments on a trusted LAN.
Before public deployment, add user/device authentication, authorization, rate limits, HTTPS/WSS,
restricted CORS, secret management, backups, and log/metric collection.

Never log join codes, raw calibration images, LLM keys, or complete pose payloads.
