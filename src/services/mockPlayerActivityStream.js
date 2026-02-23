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
    return { loss: 34, session: 105 };
  }
  if (segment === 'VIP') {
    return { loss: 15, session: 80 };
  }
  if (segment === 'New') {
    return { loss: 11, session: 52 };
  }
  return { loss: 12, session: 64 };
}

function eventWeightsForPlayer(player) {
  const segment = player.segment ?? 'Active';
  const campaignId = player.campaign?.id ?? 'loyalty';

  const weights = {
    normalBet: 3.3,
    idleDay: 1.6,
    stressBurst: segment === 'At-risk' ? 1.85 : 1.1,
    coolDown: segment === 'At-risk' ? 1.9 : 1.35,
    bankrollSwing: 1.1
  };

  if (campaignId === 'cooldown') {
    weights.coolDown += 1.0;
    weights.stressBurst = Math.max(0.3, weights.stressBurst - 0.7);
  }

  if (campaignId === 'freebet' || campaignId === 'oddsBoost') {
    weights.normalBet += 0.9;
    weights.idleDay = Math.max(0.4, weights.idleDay - 0.5);
  }

  if (campaignId === 'bonus') {
    weights.normalBet += 0.5;
  }

  return weights;
}

function randomUpdate(player) {
  const targets = segmentTargets(player.segment);
  const eventType = pickWeighted(eventWeightsForPlayer(player));

  if (eventType === 'normalBet') {
    return {
      playerId: player.id,
      daysSinceLastBet: 0,
      totalBets30d: clamp(player.totalBets30d + randomInt(1, 3), 0, 320),
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss, 14, 6),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session, 22, 9), 15, 240)
    };
  }

  if (eventType === 'idleDay') {
    return {
      playerId: player.id,
      daysSinceLastBet: clamp(player.daysSinceLastBet + 1, 0, 35),
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss + 4, 8, 5),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session - 6, 12, 7), 15, 240)
    };
  }

  if (eventType === 'stressBurst') {
    return {
      playerId: player.id,
      daysSinceLastBet: randomInt(0, 1),
      lossChange24hPercent: clamp(player.lossChange24hPercent + randomInt(10, 26), -60, 140),
      avgSessionMinutes: clamp(player.avgSessionMinutes + randomInt(14, 34), 15, 240),
      totalBets30d: clamp(player.totalBets30d + randomInt(0, 2), 0, 320)
    };
  }

  if (eventType === 'coolDown') {
    return {
      playerId: player.id,
      lossChange24hPercent: clamp(
        driftTowards(player.lossChange24hPercent, targets.loss - 8, 20, 5),
        -60,
        140
      ),
      avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session - 14, 35, 8), 15, 240)
    };
  }

  return {
    playerId: player.id,
    netDeposit30d: clamp(player.netDeposit30d + randomInt(-280, 360), 0, 12000),
    lossChange24hPercent: clamp(driftTowards(player.lossChange24hPercent, targets.loss, 10, 7), -60, 140),
    avgSessionMinutes: clamp(driftTowards(player.avgSessionMinutes, targets.session, 14, 7), 15, 240)
  };
}

export function startMockPlayerActivityStream({
  players,
  onUpdate,
  intervalMs = 2200,
  onDisconnect
}) {
  const timerId = globalThis.setInterval(() => {
    if (!Array.isArray(players) || players.length === 0) {
      return;
    }

    const player = players[Math.floor(Math.random() * players.length)];
    onUpdate(randomUpdate(player));
  }, intervalMs);

  return () => {
    globalThis.clearInterval(timerId);
    if (typeof onDisconnect === 'function') {
      onDisconnect();
    }
  };
}
