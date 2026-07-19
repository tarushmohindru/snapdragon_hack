# Backend architecture reference

Status: implemented.

```text
HTTP / WebSocket / SSE routes
        │
        ├─ SessionService ── SqliteRepository
        ├─ RuntimeRegistry ─ FrameSynchronizer (ephemeral only)
        ├─ ConnectionManager ─ WebSocket and SSE fan-out
        └─ MlClient ───────── standalone ML service
```

Design constraints:

- ML is the single source of truth for all numerical and AI processing.
- Pydantic rejects unknown fields at public boundaries.
- Frame queues and event queues are bounded.
- SQLite stores sessions, joined devices, canonical results, and AI summaries.
- Join codes are HMAC-digested; user authentication is intentionally deferred.
- Internal ML calls use a service key and explicit timeouts.
- Readiness includes an ML health check.
- One backend worker is recommended until frame pairing and live fan-out move to shared state.

See `docs/protocol.md` and `docs/operations.md` for contracts and deployment details.
