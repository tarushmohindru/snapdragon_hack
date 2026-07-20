# FormFusion

FormFusion reconstructs a live, world-space 3D skeleton from two Snapdragon phones. Each phone
runs RTMDet + RTMPose locally, sends only 2D landmarks, and receives the same calibrated 3D,
biomechanics, rep-counting, form-quality, and AI-coaching result used by the web dashboard.

## How the system fits together

```text
Phone A RTMDet/RTMPose ─┐
                        ├─ synchronized 2D landmarks ─> Backend ─> ML service
Phone B RTMDet/RTMPose ─┘                                  │          │
                                                          │          ├─ calibration
                                                          │          ├─ triangulation
                                                          │          ├─ world transform
                                                          │          ├─ biomechanics/reps
                                                          │          └─ AI coaching
                                                          │
                                                          ├─ SQLite history
                                                          ├─ WebSocket / SSE
                                                          ├─ Next.js dashboard
                                                          └─ Android live workout
```

Responsibilities are deliberately separated:

- `ml/` is the only owner of calibration, triangulation, smoothing, biomechanics, rep counting,
  form analysis, and LLM calls.
- `web-backend/` creates/join sessions, pairs frames, calls ML, persists results, and exposes
  HTTP/WebSocket/SSE APIs. It contains no inference or triangulation implementation.
- `web-frontend/` and `android_app/` consume backend contracts and render real data only.

Authentication is intentionally disabled for this development phase. A device joins with the
session ID, join code, and stable Android device ID. Do not expose this build directly to the
public internet until authentication and authorization are added.

## Quick start with Docker

Requirements: Docker Desktop and two phones on the same network as the development machine.

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Services:

- Dashboard: `http://localhost:3000`
- Backend/OpenAPI: `http://localhost:8000/docs`
- ML/OpenAPI: `http://localhost:8100/docs`

AI coaching is optional. Add either `FORMFUSION_ML_GOOGLE_API_KEY` or
`FORMFUSION_ML_SARVAM_API_KEY` to `.env`; reconstruction and biomechanics work without an LLM.

Persistent calibration files and SQLite session history live in Docker volumes. Stop services
with `docker compose down`. Add `-v` only when you intentionally want to erase those volumes.

## Run services without Docker

Python 3.12 or 3.13 is supported; Python 3.13.14 is valid for this repository.

Terminal 1 — ML:

```powershell
cd ml
Copy-Item .env.example .env
uv sync --extra dev
uv run uvicorn src.api:app --reload --port 8100
```

Terminal 2 — backend:

```powershell
cd web-backend
Copy-Item .env.example .env
uv sync --extra dev
uv run uvicorn formfusion.main:app --reload --port 8000
```

Terminal 3 — dashboard:

```powershell
cd web-frontend
Copy-Item .env.example .env.local
npm ci
npm run dev
```

The backend's `/health/ready` returns ready only when it can reach the ML service.

## Connect physical Android phones

Android Studio's bundled JDK 17 or another JDK 17 is required. Find the computer's LAN IPv4
address with `ipconfig`; use the Wi-Fi adapter address, for example `192.168.1.25`.

Build with that backend URL:

```powershell
cd android_app
.\gradlew.bat assembleDebug -PFORMFUSION_BACKEND_URL=http://192.168.1.25:8000
```

The default URL is `http://10.0.2.2:8000`, which is correct only for an Android emulator. The
backend URL is compiled into `BuildConfig`; it is not a source-code constant. Windows Firewall
must allow inbound TCP 8000, and both phones must be able to reach the computer over Wi-Fi.

## Phone workflow

1. Start ML, backend, and optionally the dashboard.
2. On phone A choose **Start with Other Phones → Host Session**.
3. The app creates a backend session, joins phone A, and shows a QR containing the backend URL,
   session ID, and join code.
4. On phone B choose **Join Session** and scan that QR. It joins the same backend session with
   its stable device ID.
5. Both phones enter calibration. Show the same 8×8 chessboard (7×7 inner corners) to both cameras. For each board
   position, tap the same numbered capture on both phones. Capture at least 10 varied positions.
6. Phone A finalizes calibration. Both phones show the real reprojection error returned by ML.
7. Start the workout on both phones. Their on-device RTM outputs stream at approximately 12 FPS;
   the backend pairs frames and broadcasts the canonical 3D result.
8. Open the web dashboard, enter the backend URL and session ID, and connect. No token is needed.
9. End the session on the host phone to persist its final duration and generate an AI report when
   an LLM provider is configured.


## Canonical live result

Every consumer receives one `pose.result` contract containing:

- world-space `x`, `y`, `z`, confidence, and observation count for each reconstructed joint;
- all calculated angles and the primary exercise angle;
- rep count, movement state, and form quality;
- source device frame IDs/timestamps and synchronization delta;
- calibration ID, units, coordinate system, reprojection error, and processing time.

The WebSocket endpoint is `/api/v1/ws/sessions/{session_id}`. A phone first sends
`device.hello`, then `pose.frame` messages. A dashboard sends a dashboard `device.hello` and only
receives results. SSE consumers can use `/api/v1/sessions/{session_id}/events`.

## Useful backend APIs

- `POST /api/v1/sessions` — create a session and join code
- `POST /api/v1/sessions/{id}/join` — register a phone
- `GET /api/v1/sessions` — session/history list
- `POST /api/v1/sessions/{id}/calibration/images` — upload a checkerboard capture
- `POST /api/v1/sessions/{id}/calibration/finalize` — solve stereo calibration in ML
- `GET /api/v1/sessions/{id}/results` — persisted canonical results
- `POST /api/v1/sessions/{id}/feedback` — latest-frame AI coaching
- `POST /api/v1/sessions/{id}/summary` — persisted AI session report
- `POST /api/v1/sessions/{id}/close` — end but retain a session

See the running backend's OpenAPI page for complete schemas.

## Verification

```powershell
cd ml
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\ruff.exe check .

cd ..\web-backend
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\ruff.exe check src tests

cd ..\web-frontend
npm run typecheck
npm run lint
npm run build

cd ..\android_app
.\gradlew.bat testDebugUnitTest
```

The Android command cannot run on a machine without a JDK. Install JDK 17 or Android Studio and
set `JAVA_HOME` before treating Android verification as complete.

## Production checklist

- Set both service keys to the same random value of at least 32 characters.
- Use HTTPS/WSS at a reverse proxy and remove Android clear-text traffic permission.
- Replace permissive development CORS origins with the deployed dashboard origin.
- Add user/device authentication before exposing session APIs publicly.
- Back up the backend SQLite volume and ML calibration volume.
- Put multiple backend replicas behind shared persistence and a cross-instance event broker;
  in-memory frame-pairing state requires session affinity.
- Configure resource limits, structured log shipping, metrics scraping, and health probes.
- Keep LLM keys only in the ML service; never place them in Android, frontend, or backend images.

## Troubleshooting

- **Backend is not ready:** verify ML is listening on port 8100 and both service keys match.
- **Phone cannot join:** open `http://<LAN-IP>:8000/health/live` in the phone browser and check
  firewall/AP isolation if it fails.
- **Frames pair but no 3D result appears:** calibration is missing or the two frame timestamps
  differ by more than `FORMFUSION_FRAME_SYNC_TOLERANCE_MS` (60 ms by default).
- **Calibration fails:** capture the entire checkerboard sharply in both cameras, vary distance
  and tilt, and avoid reusing an unchanged board pose.
- **AI returns 503:** configure one supported LLM API key in the ML service. Core pose processing
  remains available.
