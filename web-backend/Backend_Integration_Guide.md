# Backend ↔ ML integration guide

The backend does not import numerical ML modules. It communicates with the standalone ML service
through `services/ml_client.py`, using the shared JSON contract represented in both projects.

## Request path

1. Android phones send RTMPose 2D observations to the backend WebSocket.
2. `FrameSynchronizer` pairs the nearest frames within the configured tolerance.
3. `MlClient.reconstruct` sends both observations to `POST /v1/reconstruct` on ML.
4. ML loads the session calibration and performs triangulation, world transformation, filtering,
   biomechanics, rep counting, and form analysis.
5. The backend validates the returned canonical result, persists it in SQLite, and broadcasts it
   unchanged to phones, dashboards, and SSE consumers.

Calibration uploads/finalization and AI feedback/summary calls follow the same proxy pattern. The
backend authenticates to ML with `X-ML-Service-Key`; browser and phone clients never receive that
key.

When a contract changes, update `ml/src/contracts.py`, the matching backend Pydantic contract,
Android parsing, and frontend TypeScript types in the same change. Do not reintroduce NumPy,
OpenCV, triangulation, biomechanics, or LLM provider code into the backend.
