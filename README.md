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
- Spring Boot backend module with REST + SSE in `backend/`
- Legacy Java 21 backend (`HttpServer` + SSE) kept as fallback
- Local file persistence by default
- Vitest + Vue Test Utils

## Architecture

- `src/engine/retentionModel.js`: shared retention rules and KPI logic.
- `backend/`: Spring Boot Maven module that mirrors the current `/api/...` contract with REST controllers and SSE.
- `java-backend/src/com/mozzart/retention/RetentionApplication.java`: legacy Java API, SSE stream, static hosting, and persistence wiring.
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
RETENTION_DATA_FILE=.retention-spring-backend.json
```

5. Start frontend + backend together:

```bash
npm run dev
```

6. Open `http://localhost:5174`.

Useful scripts:
- `npm run dev:frontend` starts only Vite.
- `npm run dev` now starts the Spring Boot backend by default and waits for `/api/health` before launching Vite.
- `npm run restart:dev` stops anything already using the dev ports and starts the full Spring + Vite flow again.
- `npm run dev:backend` starts the Spring Boot backend module on `http://localhost:8787`.
- `npm run dev:backend:legacy` starts the older Java backend if you need the previous runtime.
- `npm run build:backend` runs Maven package for the Spring Boot backend.
- `npm run build:backend:legacy` compiles the legacy Java backend only.
- `npm run build:backend:spring` runs Maven tests/package for the Spring Boot backend.
- To expose backend on LAN (for device testing), set `RETENTION_API_HOST=0.0.0.0`.
- Set `RETENTION_PERSISTENCE=memory` to force in-memory mode.

## Spring Boot Module

- The new Spring Boot backend lives in `backend/`.
- It exposes the same core API contract: `/api/health`, `/api/state`, `/api/players`, `/api/interventions`, `/api/activity`, `POST /api/players/{id}/activity`, and `/api/stream`.
- It currently reuses the Java retention engine logic by copying `RetentionDomain.java` into the Maven module.
- Persistence is still file-or-memory based for now; PostgreSQL can be added next through Spring Data.
- If `mvn` is not found in your shell, fix your Maven `PATH` first before using the Spring scripts.

### Dev Troubleshooting

- `EADDRINUSE` (`8787` or frontend port already in use):
  - Bash: `RETENTION_API_PORT=8788 npm run dev`
  - PowerShell: `$env:RETENTION_API_PORT=8788; npm run dev`
  - CMD: `set RETENTION_API_PORT=8788 && npm run dev`
- If the Spring backend cannot find `mvn`, install Maven or set `MAVEN_HOME`, then reopen the terminal.
- If the legacy Java backend cannot find `java` or `javac`, install JDK 21 and reopen the terminal.

## Persistence

- The Spring Boot backend persists players and activity history to a local JSON file by default.
- First run seeds data from the current Java retention-domain seed dataset.
- Every activity update is written back to the file, so state survives restart.
- Activity history can be read via `GET /api/activity`.
- Check current mode via `GET /api/health` (`persistenceMode` is `file` or `memory`).
- The legacy Java backend is still available if you need it, but it is no longer the default dev runtime.

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
