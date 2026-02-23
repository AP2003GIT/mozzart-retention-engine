import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { seedPlayers } from '../src/data/seedPlayers.js';
import { sanitizeExperiment, toNumber } from '../src/engine/retentionModel.js';

const PLAYER_COLUMNS_SQL = `
  id,
  name,
  market,
  days_since_joined,
  days_since_last_bet,
  total_bets_30d,
  loss_change_24h_percent,
  avg_session_minutes,
  net_deposit_30d,
  experiment_group,
  experiment_accepted
`;

function parseDotEnv(content) {
  const parsed = {};
  const lines = content.split(/\r?\n/u);

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }

    const separatorIndex = line.indexOf('=');
    if (separatorIndex < 1) {
      continue;
    }

    const key = line.slice(0, separatorIndex).trim();
    let value = line.slice(separatorIndex + 1).trim();

    if (!/^[A-Za-z_][A-Za-z0-9_]*$/u.test(key)) {
      continue;
    }

    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }

    parsed[key] = value;
  }

  return parsed;
}

function loadEnvFile(projectRoot) {
  const envPath = path.join(projectRoot, '.env');
  if (!existsSync(envPath)) {
    return;
  }

  const parsed = parseDotEnv(readFileSync(envPath, 'utf8'));
  for (const [key, value] of Object.entries(parsed)) {
    if (process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
}

function normalizePlayer(player) {
  const experiment = sanitizeExperiment(player.experiment);

  return {
    id: String(player.id),
    name: String(player.name ?? ''),
    market: String(player.market ?? ''),
    daysSinceJoined: Math.max(0, Math.trunc(toNumber(player.daysSinceJoined, 0))),
    daysSinceLastBet: Math.max(0, Math.trunc(toNumber(player.daysSinceLastBet, 0))),
    totalBets30d: Math.max(0, Math.trunc(toNumber(player.totalBets30d, 0))),
    lossChange24hPercent: toNumber(player.lossChange24hPercent, 0),
    avgSessionMinutes: Math.max(0, Math.trunc(toNumber(player.avgSessionMinutes, 0))),
    netDeposit30d: Math.max(0, toNumber(player.netDeposit30d, 0)),
    experiment
  };
}

function clonePlayer(player) {
  return {
    ...player,
    experiment: {
      ...player.experiment
    }
  };
}

function toDbRecord(player) {
  const normalized = normalizePlayer(player);

  return {
    id: normalized.id,
    name: normalized.name,
    market: normalized.market,
    days_since_joined: normalized.daysSinceJoined,
    days_since_last_bet: normalized.daysSinceLastBet,
    total_bets_30d: normalized.totalBets30d,
    loss_change_24h_percent: normalized.lossChange24hPercent,
    avg_session_minutes: normalized.avgSessionMinutes,
    net_deposit_30d: normalized.netDeposit30d,
    experiment_group: normalized.experiment.group,
    experiment_accepted: normalized.experiment.accepted
  };
}

function fromDbRow(row) {
  return normalizePlayer({
    id: row.id,
    name: row.name,
    market: row.market,
    daysSinceJoined: row.days_since_joined,
    daysSinceLastBet: row.days_since_last_bet,
    totalBets30d: row.total_bets_30d,
    lossChange24hPercent: row.loss_change_24h_percent,
    avgSessionMinutes: row.avg_session_minutes,
    netDeposit30d: row.net_deposit_30d,
    experiment: {
      group: row.experiment_group,
      accepted: row.experiment_accepted
    }
  });
}

function createMemoryStore() {
  let records = seedPlayers.map((player) => normalizePlayer(player));
  let activityEvents = [];

  return {
    mode: 'memory',
    async listPlayers() {
      return records.map((player) => clonePlayer(player));
    },
    async upsertPlayer(player) {
      const nextRecord = normalizePlayer(player);
      const index = records.findIndex((record) => record.id === nextRecord.id);

      if (index === -1) {
        records = [...records, nextRecord];
        return;
      }

      records[index] = nextRecord;
    },
    async recordActivityEvent({ playerId, source = 'api', update = {} }) {
      activityEvents = [
        {
          id: activityEvents.length + 1,
          playerId: String(playerId),
          source: String(source),
          update,
          createdAt: new Date().toISOString()
        },
        ...activityEvents
      ];
    },
    async listActivityEvents(limit = 50) {
      return activityEvents.slice(0, Math.max(1, Math.trunc(limit)));
    },
    async close() {}
  };
}

async function ensureSchema(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS players (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      market TEXT NOT NULL,
      days_since_joined INTEGER NOT NULL,
      days_since_last_bet INTEGER NOT NULL,
      total_bets_30d INTEGER NOT NULL,
      loss_change_24h_percent DOUBLE PRECISION NOT NULL,
      avg_session_minutes INTEGER NOT NULL,
      net_deposit_30d DOUBLE PRECISION NOT NULL,
      experiment_group TEXT NOT NULL CHECK (experiment_group IN ('A', 'B')),
      experiment_accepted BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);

  await pool.query(`
    CREATE TABLE IF NOT EXISTS player_activity_events (
      id BIGSERIAL PRIMARY KEY,
      player_id TEXT NOT NULL REFERENCES players(id) ON DELETE CASCADE,
      source TEXT NOT NULL,
      update_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
}

async function seedIfEmpty(pool) {
  const countResult = await pool.query('SELECT COUNT(*)::INT AS count FROM players;');
  const existingCount = Number(countResult.rows[0]?.count ?? 0);

  if (existingCount > 0) {
    return;
  }

  for (const player of seedPlayers) {
    const record = toDbRecord(player);

    await pool.query(
      `
        INSERT INTO players (
          ${PLAYER_COLUMNS_SQL}
        ) VALUES (
          $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11
        );
      `,
      [
        record.id,
        record.name,
        record.market,
        record.days_since_joined,
        record.days_since_last_bet,
        record.total_bets_30d,
        record.loss_change_24h_percent,
        record.avg_session_minutes,
        record.net_deposit_30d,
        record.experiment_group,
        record.experiment_accepted
      ]
    );
  }
}

async function createPostgresStore(databaseUrl) {
  let Pool;
  try {
    ({ Pool } = await import('pg'));
  } catch {
    throw new Error('Missing `pg` package. Run `npm install` to enable PostgreSQL persistence.');
  }

  const needsSslByDefault = databaseUrl.includes('neon.tech');
  const pool = new Pool({
    connectionString: databaseUrl,
    ssl: needsSslByDefault ? { rejectUnauthorized: false } : undefined
  });

  await pool.query('SELECT 1;');
  await ensureSchema(pool);
  await seedIfEmpty(pool);

  return {
    mode: 'postgres',
    async listPlayers() {
      const result = await pool.query(
        `
          SELECT ${PLAYER_COLUMNS_SQL}
          FROM players
          ORDER BY id;
        `
      );

      return result.rows.map((row) => fromDbRow(row));
    },
    async upsertPlayer(player) {
      const record = toDbRecord(player);

      await pool.query(
        `
          INSERT INTO players (
            ${PLAYER_COLUMNS_SQL}
          ) VALUES (
            $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11
          )
          ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            market = EXCLUDED.market,
            days_since_joined = EXCLUDED.days_since_joined,
            days_since_last_bet = EXCLUDED.days_since_last_bet,
            total_bets_30d = EXCLUDED.total_bets_30d,
            loss_change_24h_percent = EXCLUDED.loss_change_24h_percent,
            avg_session_minutes = EXCLUDED.avg_session_minutes,
            net_deposit_30d = EXCLUDED.net_deposit_30d,
            experiment_group = EXCLUDED.experiment_group,
            experiment_accepted = EXCLUDED.experiment_accepted,
            updated_at = NOW();
        `,
        [
          record.id,
          record.name,
          record.market,
          record.days_since_joined,
          record.days_since_last_bet,
          record.total_bets_30d,
          record.loss_change_24h_percent,
          record.avg_session_minutes,
          record.net_deposit_30d,
          record.experiment_group,
          record.experiment_accepted
        ]
      );
    },
    async recordActivityEvent({ playerId, source = 'api', update = {} }) {
      await pool.query(
        `
          INSERT INTO player_activity_events (
            player_id,
            source,
            update_payload
          ) VALUES ($1, $2, $3::jsonb);
        `,
        [String(playerId), String(source), JSON.stringify(update ?? {})]
      );
    },
    async listActivityEvents(limit = 50) {
      const safeLimit = Math.max(1, Math.min(200, Math.trunc(toNumber(limit, 50))));
      const result = await pool.query(
        `
          SELECT
            id,
            player_id,
            source,
            update_payload,
            created_at
          FROM player_activity_events
          ORDER BY id DESC
          LIMIT $1;
        `,
        [safeLimit]
      );

      return result.rows.map((row) => ({
        id: Number(row.id),
        playerId: String(row.player_id),
        source: String(row.source),
        update: row.update_payload ?? {},
        createdAt:
          row.created_at instanceof Date ? row.created_at.toISOString() : String(row.created_at)
      }));
    },
    async close() {
      await pool.end();
    }
  };
}

export async function createPlayerStore({ projectRoot }) {
  loadEnvFile(projectRoot);

  const databaseUrl = process.env.DATABASE_URL?.trim();
  if (!databaseUrl) {
    return createMemoryStore();
  }

  return createPostgresStore(databaseUrl);
}
