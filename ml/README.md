# FormFusion ML service

This folder is the single source of truth for calibration, stereo triangulation, smoothing,
biomechanics, rep counting, and AI coaching.

The Snapdragon Android application performs RTMDet and RTMPose inference on-device and sends
2D landmarks. The ML service combines synchronized observations from both phones and returns
the canonical world-space 3D result.

```powershell
Copy-Item .env.example .env
uv sync --extra dev
uv run uvicorn src.api:app --reload --port 8100
```

Internal API documentation is available at `http://localhost:8100/docs`.
