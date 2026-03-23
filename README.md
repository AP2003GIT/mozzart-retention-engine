# Mozzart Retention Engine

Retention + Responsible Gaming MVP for Mozzart Bet.

## What it does

- Segments players into `New`, `Active`, `At-risk`, and `VIP`
- Evaluates operational triggers:
  - inactivity for 7+ days
  - loss spike in 24h
  - long sessions (120+ minutes)
- Assigns campaigns automatically:
  - onboarding bonus
  - reactivation freebet
  - VIP odds boost
  - cooldown suggestion
  - loyalty mission
- Shows CRM and Risk queue with priority ordering
- Tracks campaign A/B outcomes per campaign family
- Streams player behavior in near real time from backend API (with local mock fallback)
- Uses rolling risk baselines and hysteresis (enter/exit buffers) to avoid unstable `At-risk` flipping

## Stack

- Vue 3
- Pinia
- Vite
- Java 21 backend (`HttpServer` + SSE)
- Local file persistence by default
- Vitest + Vue Test Utils

## Architecture

- `src/engine/retentionModel.js`: shared retention rules and KPI logic.
- `java-backend/src/com/mozzart/retention/RetentionApplication.java`: Java API, SSE stream, static hosting, and persistence wiring.
- `java-backend/src/com/mozzart/retention/RetentionDomain.java`: Java port of the retention model, KPI logic, interventions, and simulation.
- `scripts/run-java-backend.mjs`: cross-platform compile + run bridge used by npm scripts.
- `src/stores/retentionEngine.js`: frontend state layer consuming backend stream first, then fallback mock stream.

## API Endpoints

- `GET /api/health`
- `GET /api/state`
- `GET /api/players`
- `GET /api/interventions`
- `GET /api/activity?limit=50`
- `GET /api/stream` (Server-Sent Events)
- `POST /api/players/:id/activity`

## Local Run (Windows + macOS + Linux)

1. Install Node.js 20+.
2. Install Java/JDK 21+ (`java` and `javac` must be available).
3. Install frontend dependencies:
```bash
npm install
```

4. Optional: add `.env` in project root:

```env
RETENTION_PERSISTENCE=file
RETENTION_DATA_FILE=.retention-java-backend.json
```

5. Start frontend + backend together:

```bash
npm run dev
```

6. Open `http://localhost:5174`.

Useful scripts:
- `npm run dev:frontend` starts only Vite.
- `npm run dev:backend` compiles and starts the Java backend on `http://localhost:8787`.
- `npm run build:backend` compiles the Java backend only.
- To expose backend on LAN (for device testing), set `RETENTION_API_HOST=0.0.0.0`.
- Set `RETENTION_PERSISTENCE=memory` to force in-memory mode.

### Dev Troubleshooting

- `EADDRINUSE` (`8787` or frontend port already in use):
  - Bash: `RETENTION_API_PORT=8788 npm run dev`
  - PowerShell: `$env:RETENTION_API_PORT=8788; npm run dev`
  - CMD: `set RETENTION_API_PORT=8788 && npm run dev`
- If the Java backend cannot find `java` or `javac`, install JDK 21 and reopen the terminal.

## Persistence

- The Java backend persists players and activity history to a local JSON file by default.
- First run seeds data from the Java copy of the current seed players.
- Every activity update is written back to the file, so state survives restart.
- Activity history can be read via `GET /api/activity`.
- Check current mode via `GET /api/health` (`persistenceMode` is `file` or `memory`).
- If `DATABASE_URL` is set, the Java backend currently ignores it and logs a fallback message because no PostgreSQL JDBC driver is bundled yet.

## Risk Model Notes

- Risk score is continuous and uses inactivity, loss/session deltas vs rolling baselines, and volatility.
- `At-risk` entry requires sustained risk; exit requires multiple healthy updates.
- Severe signals (very high inactivity/loss/session) can still trigger immediate `At-risk`.
- Trigger/risk calibration is tuned to produce realistic intervention volume instead of near-zero or always-at-risk behavior.

Quick verify:

```bash
curl -s http://127.0.0.1:8787/api/health
curl -s http://127.0.0.1:8787/api/state
curl -s "http://127.0.0.1:8787/api/activity?limit=10"
```

## Production Run

Build frontend:

```bash
npm run build
```

Start unified backend + static host:

```bash
npm run start
```

Open `http://localhost:8787`. The Java backend serves both the API and built frontend assets.

## Android Launch (PWA)

This app is installable as a PWA:
- `public/manifest.webmanifest`
- `public/service-worker.js`

Steps:
1. Deploy or run a production build.
2. Open the app in Chrome on Android.
3. Tap menu -> `Add to Home screen` (or `Install app`).
4. Launch from the Android home screen as a standalone app.

## Test

```bash
npm run test
```
