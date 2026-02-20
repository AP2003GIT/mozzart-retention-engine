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
- Streams mocked player behavior in near real time

## Stack

- Vue 3
- Pinia
- Vite
- Vitest + Vue Test Utils

## Run

```bash
npm install
npm run dev
```

## Test

```bash
npm run test
```
