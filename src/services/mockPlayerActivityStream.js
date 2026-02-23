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

function randomUpdate(player) {
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
