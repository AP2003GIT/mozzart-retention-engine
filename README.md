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
- Node.js backend (`http` + SSE)
- PostgreSQL persistence via `pg` (Neon-ready)
- Vitest + Vue Test Utils

## Architecture

- `src/engine/retentionModel.js`: shared retention rules and KPI logic.
- `server/index.js`: backend API and stream.
- `server/playerStore.js`: persistence adapter (Postgres or memory fallback).
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
2. Install dependencies (`pg` included):
```bash
npm install
```

3. Optional: add `.env` in project root for PostgreSQL persistence:

```env
DATABASE_URL="postgresql://...your-neon-connection-string..."
```

4. Start frontend + backend together:

```bash
npm run dev
```

5. Open `http://localhost:5174`.

Useful scripts:
- `npm run dev:frontend` starts only Vite.
- `npm run dev:backend` starts only backend on `http://localhost:8787`.
- To expose backend on LAN (for device testing), set `RETENTION_API_HOST=0.0.0.0`.
- If `DATABASE_URL` is missing, backend falls back to memory mode.

### Dev Troubleshooting

- `EADDRINUSE` (`8787` or frontend port already in use):
  - Bash: `RETENTION_API_PORT=8788 npm run dev`
  - PowerShell: `$env:RETENTION_API_PORT=8788; npm run dev`
  - CMD: `set RETENTION_API_PORT=8788 && npm run dev`
- Neon DNS/network lookup error (`EAI_AGAIN`):
  - Start in memory mode for now:
    - Bash: `DATABASE_URL= npm run dev`
    - PowerShell: `$env:DATABASE_URL=''; npm run dev`
    - CMD: `set DATABASE_URL= && npm run dev`

## Postgres Persistence

- Startup auto-creates `players` table if it does not exist.
- Startup auto-creates `player_activity_events` table for update history.
- First run seeds table from `src/data/seedPlayers.js` only when table is empty.
- Every activity update is persisted to DB, so data survives restart.
- Activity history can be read via `GET /api/activity`.
- Check current mode via `GET /api/health` (`persistenceMode` is `postgres` or `memory`).

## Risk Model Notes

- Risk score is continuous and uses inactivity, loss/session deltas vs rolling baselines, and volatility.
- `At-risk` entry requires sustained risk; exit requires multiple healthy updates.
- Severe signals (very high inactivity/loss/session) can still trigger immediate `At-risk`.
- Trigger/risk calibration is tuned to produce realistic intervention volume instead of near-zero or always-at-risk behavior.

Quick verify:

```bash
curl -s http://127.0.0.1:8787/api/health
curl -s "http://127.0.0.1:8787/api/activity?limit=10"
```

Neon SQL check:

```sql
SELECT id, player_id, source, update_payload, created_at
FROM player_activity_events
ORDER BY id DESC
LIMIT 10;
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

Open `http://localhost:8787`.

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
