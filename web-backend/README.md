# FormFusion backend

FastAPI orchestration service for token-free development sessions, timestamp pairing, durable
SQLite history, ML-service calls, WebSocket/SSE streaming, calibration proxying, and AI APIs.

This service intentionally contains no calibration math, triangulation, filtering, biomechanics,
rep counting, or LLM implementation. Those responsibilities belong to `../ml`.

```powershell
Copy-Item .env.example .env
uv sync --extra dev
uv run uvicorn formfusion.main:app --reload --port 8000
```

The ML service must be running at `FORMFUSION_ML_SERVICE_URL`. OpenAPI is available at `/docs`,
metrics at `/metrics`, and health checks at `/health/live` and `/health/ready`.

```powershell
.\.venv\Scripts\ruff.exe check src tests scripts
.\.venv\Scripts\python.exe -m pytest -q
```

See `../README.md` for the complete system workflow, `docs/protocol.md` for wire contracts, and
`docs/operations.md` for deployment configuration.
