import { createServer } from 'node:http';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  enrichPlayers,
  buildInterventions,
  buildKpis,
  buildExperimentSummary,
  applyActivityUpdateToPlayers
} from '../src/engine/retentionModel.js';
import { createPlayerStore } from './playerStore.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const distRoot = path.join(projectRoot, 'dist');

const API_PORT = Number(process.env.RETENTION_API_PORT ?? 8787);
const API_HOST = process.env.RETENTION_API_HOST ?? '127.0.0.1';
const STREAM_INTERVAL_MS = Number(process.env.RETENTION_STREAM_MS ?? 2200);

const MIME_TYPES = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp'
};

let players = [];
let lastUpdatedAt = null;
let playerStore = null;
let persistenceMode = 'memory';
let simulationTimer = null;
let shuttingDown = false;
let updateQueue = Promise.resolve(null);
const streamClients = new Set();

function setCorsHeaders(response) {
  response.setHeader('Access-Control-Allow-Origin', '*');
  response.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  response.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function sendJson(response, statusCode, payload) {
  setCorsHeaders(response);
  response.statusCode = statusCode;
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.end(JSON.stringify(payload));
}

function snapshotPayload() {
  return {
    players,
    interventions: buildInterventions(players).slice(0, 10),
    kpis: buildKpis(players),
    experimentSummary: buildExperimentSummary(players),
    lastUpdatedAt,
    persistenceMode
  };
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function driftTowards(current, target, maxStep, noise = 0) {
  const delta = target - current;
  const boundedStep = clamp(delta, -maxStep, maxStep);
  const jitter = noise === 0 ? 0 : randomInt(-noise, noise);
  return current + boundedStep + jitter;
}

function pickWeighted(weights) {
  const entries = Object.entries(weights).filter(([, weight]) => weight > 0);
  const total = entries.reduce((sum, [, weight]) => sum + weight, 0);
  if (total <= 0) {
    return entries[0]?.[0] ?? 'normalBet';
  }

  let cursor = Math.random() * total;
  for (const [key, weight] of entries) {
    cursor -= weight;
    if (cursor <= 0) {
      return key;
    }
  }

  return entries[entries.length - 1][0];
}

function segmentTargets(segment) {
  if (segment === 'At-risk') {
    return { loss: 42, session: 122 };
  }
  if (segment === 'VIP') {
    return { loss: 18, session: 90 };
  }
  if (segment === 'New') {
    return { loss: 14, session: 58 };
  }
  return { loss: 17, session: 74 };
}

function eventWeightsForPlayer(player) {
  const segment = player.segment ?? 'Active';
  const campaignId = player.campaign?.id ?? 'loyalty';

  const weights = {
    normalBet: 2.4,
    idleDay: 2.2,
    stressBurst: segment === 'At-risk' ? 2.8 : 1.8,
    coolDown: segment === 'At-risk' ? 1.4 : 1.1,
    bankrollSwing: 1.3
  };

  if (campaignId === 'cooldown') {
    weights.coolDown += 0.8;
    weights.stressBurst = Math.max(0.6, weights.stressBurst - 0.4);
  }

  if (campaignId === 'freebet' || campaignId === 'oddsBoost') {
    weights.normalBet += 0.6;
    weights.idleDay = Math.max(0.6, weights.idleDay - 0.2);
  }

  if (campaignId === 'bonus') {
    weights.normalBet += 0.4;
  }

  return weights;
}

function randomUpdate(sourcePlayers) {
  if (!Array.isArray(sourcePlayers) || sourcePlayers.length === 0) {
    return null;
  }

  const player = sourcePlayers[Math.floor(Math.random() * sourcePlayers.length)];
  const targets = segmentTargets(player.segment);
  const eventType = pickWeighted(eventWeightsForPlayer(player));

  if (eventType === 'normalBet') {
    return {
      playerId: player.id,
      daysSinceLastBet: 0,
      totalBets30d: clamp(player.totalBets30d + randomInt(1, 3), 0, 320),
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss, 12, 7),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session, 18, 10), 15, 240)
    };
  }

  if (eventType === 'idleDay') {
    return {
      playerId: player.id,
      daysSinceLastBet: clamp(player.daysSinceLastBet + randomInt(1, 2), 0, 35),
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss + 8, 10, 6),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session + 6, 12, 9), 15, 240)
    };
  }

  if (eventType === 'stressBurst') {
    return {
      playerId: player.id,
      daysSinceLastBet: randomInt(0, 2),
      lossChange24hPercent: clamp(player.lossChange24hPercent + randomInt(14, 32), -60, 140),
      avgSessionMinutes: clamp(player.avgSessionMinutes + randomInt(18, 40), 15, 240),
      totalBets30d: clamp(player.totalBets30d + randomInt(0, 2), 0, 320)
    };
  }

  if (eventType === 'coolDown') {
    return {
      playerId: player.id,
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss - 12, 18, 6),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session - 20, 28, 9), 15, 240)
    };
  }

  return {
    playerId: player.id,
    netDeposit30d: clamp(player.netDeposit30d + randomInt(-280, 360), 0, 12000),
    lossChange24hPercent: clamp(driftTowards(player.lossChange24hPercent, targets.loss + 3, 12, 8), -60, 140),
    avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session + 2, 16, 8), 15, 240)
  };
}

function parseLimit(value, fallback = 50) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.max(1, Math.min(200, Math.trunc(parsed)));
}

function writeStreamEvent(response, payload) {
  response.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function broadcast(payload) {
  for (const client of streamClients) {
    try {
      writeStreamEvent(client, payload);
    } catch {
      streamClients.delete(client);
    }
  }
}

async function applyUpdateInternal(update, source = 'api') {
  const nextPlayers = applyActivityUpdateToPlayers(players, update);
  if (nextPlayers === players) {
    return null;
  }

  const updatedPlayer = nextPlayers.find((player) => player.id === update.playerId);
  if (!updatedPlayer) {
    return null;
  }

  await playerStore.upsertPlayer(updatedPlayer);
  await playerStore.recordActivityEvent({
    playerId: updatedPlayer.id,
    source,
    update
  });

  players = nextPlayers;
  lastUpdatedAt = new Date().toISOString();
  return {
    type: 'activity',
    update,
    source,
    lastUpdatedAt
  };
}

function queueUpdate(update, source = 'api') {
  const task = updateQueue.then(() => applyUpdateInternal(update, source));
  updateQueue = task.catch(() => null);
  return task;
}

function parseJsonBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];

    request.on('data', (chunk) => {
      chunks.push(chunk);
    });

    request.on('end', () => {
      if (chunks.length === 0) {
        resolve({});
        return;
      }

      try {
        const body = Buffer.concat(chunks).toString('utf8');
        resolve(JSON.parse(body));
      } catch (error) {
        reject(error);
      }
    });

    request.on('error', reject);
  });
}

async function fileExists(filePath) {
  try {
    const stat = await fs.stat(filePath);
    return stat.isFile();
  } catch {
    return false;
  }
}

async function serveStaticAsset(requestPath, response) {
  const normalizedPath = requestPath === '/' ? '/index.html' : requestPath;
  const targetPath = path.join(distRoot, decodeURIComponent(normalizedPath));
  const safePrefix = `${distRoot}${path.sep}`;

  if (!targetPath.startsWith(safePrefix) && targetPath !== path.join(distRoot, 'index.html')) {
    sendJson(response, 403, { error: 'forbidden' });
    return true;
  }

  const assetExists = await fileExists(targetPath);
  if (assetExists) {
    const extension = path.extname(targetPath);
    const mimeType = MIME_TYPES[extension] ?? 'application/octet-stream';
    const content = await fs.readFile(targetPath);

    response.statusCode = 200;
    response.setHeader('Content-Type', mimeType);
    response.end(content);
    return true;
  }

  const indexPath = path.join(distRoot, 'index.html');
  const hasIndex = await fileExists(indexPath);

  if (!hasIndex) {
    sendJson(response, 404, {
      error: 'frontend-not-built',
      hint: 'Run `npm run build` before `npm run start`.'
    });
    return true;
  }

  const indexContent = await fs.readFile(indexPath);
  response.statusCode = 200;
  response.setHeader('Content-Type', 'text/html; charset=utf-8');
  response.end(indexContent);
  return true;
}

async function routeRequest(request, response) {
  if (!request.url) {
    sendJson(response, 400, { error: 'missing-url' });
    return;
  }

  const requestUrl = new URL(request.url, `http://${request.headers.host ?? 'localhost'}`);
  const pathname = requestUrl.pathname;

  if (request.method === 'OPTIONS') {
    setCorsHeaders(response);
    response.statusCode = 204;
    response.end();
    return;
  }

  if (request.method === 'GET' && pathname === '/api/health') {
    sendJson(response, 200, {
      status: 'ok',
      service: 'mozzart-retention-engine-backend',
      persistenceMode,
      players: players.length,
      now: new Date().toISOString()
    });
    return;
  }

  if (request.method === 'GET' && pathname === '/api/state') {
    sendJson(response, 200, snapshotPayload());
    return;
  }

  if (request.method === 'GET' && pathname === '/api/players') {
    sendJson(response, 200, { players, lastUpdatedAt, persistenceMode });
    return;
  }

  if (request.method === 'GET' && pathname === '/api/interventions') {
    sendJson(response, 200, {
      interventions: buildInterventions(players).slice(0, 10),
      lastUpdatedAt,
      persistenceMode
    });
    return;
  }

  if (request.method === 'GET' && pathname === '/api/activity') {
    const limit = parseLimit(requestUrl.searchParams.get('limit'), 50);
    const events = await playerStore.listActivityEvents(limit);

    sendJson(response, 200, {
      events,
      count: events.length,
      persistenceMode
    });
    return;
  }

  if (request.method === 'POST' && pathname.startsWith('/api/players/')) {
    const match = pathname.match(/^\/api\/players\/([^/]+)\/activity$/);

    if (!match) {
      sendJson(response, 404, { error: 'not-found' });
      return;
    }

    let payload;
    try {
      payload = await parseJsonBody(request);
    } catch {
      sendJson(response, 400, { error: 'invalid-json-body' });
      return;
    }

    const update = {
      ...payload,
      playerId: decodeURIComponent(match[1])
    };

    try {
      const event = await queueUpdate(update, 'api');

      if (!event) {
        sendJson(response, 404, { error: 'player-not-found' });
        return;
      }

      broadcast(event);
      sendJson(response, 200, {
        ok: true,
        event
      });
      return;
    } catch (error) {
      sendJson(response, 500, {
        error: 'update-persistence-failed',
        message: error instanceof Error ? error.message : 'unknown-error'
      });
      return;
    }
  }

  if (request.method === 'GET' && pathname === '/api/stream') {
    setCorsHeaders(response);
    response.statusCode = 200;
    response.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
    response.setHeader('Cache-Control', 'no-cache, no-transform');
    response.setHeader('Connection', 'keep-alive');
    response.flushHeaders?.();

    const client = response;
    streamClients.add(client);

    writeStreamEvent(client, {
      type: 'snapshot',
      players,
      lastUpdatedAt,
      persistenceMode
    });

    request.on('close', () => {
      streamClients.delete(client);
    });

    return;
  }

  if (pathname.startsWith('/api/')) {
    sendJson(response, 404, { error: 'not-found' });
    return;
  }

  await serveStaticAsset(pathname, response);
}

const server = createServer((request, response) => {
  routeRequest(request, response).catch((error) => {
    sendJson(response, 500, {
      error: 'internal-server-error',
      message: error instanceof Error ? error.message : 'unknown-error'
    });
  });
});

function startSimulation() {
  simulationTimer = globalThis.setInterval(() => {
    const update = randomUpdate(players);
    if (!update) {
      return;
    }

    queueUpdate(update, 'simulation')
      .then((event) => {
        if (event) {
          broadcast(event);
        }
      })
      .catch((error) => {
        // eslint-disable-next-line no-console
        console.error('Simulation update failed:', error);
      });
  }, STREAM_INTERVAL_MS);
}

async function shutdown() {
  if (shuttingDown) {
    return;
  }
  shuttingDown = true;

  if (simulationTimer) {
    globalThis.clearInterval(simulationTimer);
    simulationTimer = null;
  }

  for (const client of streamClients) {
    client.end();
  }
  streamClients.clear();

  await playerStore?.close();

  server.close(() => {
    process.exit(0);
  });
}

process.on('SIGINT', () => {
  shutdown().catch(() => process.exit(1));
});
process.on('SIGTERM', () => {
  shutdown().catch(() => process.exit(1));
});

async function bootstrap() {
  playerStore = await createPlayerStore({ projectRoot });
  persistenceMode = playerStore.mode;
  players = enrichPlayers(await playerStore.listPlayers());

  startSimulation();

  server.listen(API_PORT, API_HOST, () => {
    // eslint-disable-next-line no-console
    console.log(
      `Retention backend running on http://${API_HOST}:${API_PORT} (persistence: ${persistenceMode})`
    );
  });
}

bootstrap().catch((error) => {
  // eslint-disable-next-line no-console
  console.error('Failed to start backend:', error);
  process.exit(1);
});
