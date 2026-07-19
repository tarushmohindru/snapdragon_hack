# FormFusion Web Dashboard

Production-oriented Next.js dashboard for live 3D biomechanics sessions.

## Features

- Real token-free backend session connection for the current development phase
- Resilient WebSocket reconnect and heartbeat handling
- Live 3D skeleton built from backend `joints_3d`
- Joint-angle telemetry and bounded session trace
- Rep count and backend-supplied form assessment
- Calibration quality, camera count, pairing latency, and stale-data states
- ML-backed live coaching cues and persisted session reports

No mock or generated pose data is included. Every metric shown in the dashboard comes from the
FormFusion backend.

## Run

```powershell
Copy-Item .env.example .env.local
npm install
npm run dev
```

Open `http://localhost:3000`, then enter the backend URL and session ID.

## Verify

```powershell
npm run lint
npm run typecheck
npm run build
```

Set `NEXT_PUBLIC_BACKEND_URL` to the deployed HTTPS backend URL in production. The browser will
automatically use the corresponding secure `wss://` WebSocket URL.
