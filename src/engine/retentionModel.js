export const DEFAULT_FILTERS = {
  segment: 'All',
  trigger: 'All',
  search: ''
};

export const TRIGGER_LABELS = {
  inactivity7d: 'Inactivity 7d',
  lossSpike24h: 'Loss spike 24h',
  longSessions: 'Session >120m'
};

export const FILTER_TRIGGER_TO_CODE = {
  [TRIGGER_LABELS.inactivity7d]: 'inactivity7d',
  [TRIGGER_LABELS.lossSpike24h]: 'lossSpike24h',
  [TRIGGER_LABELS.longSessions]: 'longSessions'
};

export const CAMPAIGNS = {
  cooldown: {
    id: 'cooldown',
    label: 'Cooldown suggestion',
    owner: 'Risk'
  },
  oddsBoost: {
    id: 'oddsBoost',
    label: 'VIP odds boost',
    owner: 'CRM'
  },
  freebet: {
    id: 'freebet',
    label: 'Reactivation freebet',
    owner: 'CRM'
  },
  bonus: {
    id: 'bonus',
    label: 'Onboarding bonus',
    owner: 'CRM'
  },
  loyalty: {
    id: 'loyalty',
    label: 'Loyalty mission',
    owner: 'CRM'
  }
};

const SEGMENT_ORDER = {
  'At-risk': 1,
  VIP: 2,
  New: 3,
  Active: 4
};

const ENTRY_RISK_THRESHOLD = 58;
const EXIT_RISK_THRESHOLD = 44;
const LOSS_BASELINE_MIN = 8;
const SESSION_BASELINE_MIN = 30;
const EXTREME_INACTIVITY_DAYS = 10;
const EXTREME_LOSS_PERCENT = 85;
const EXTREME_SESSION_MINUTES = 170;

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function ema(currentValue, nextValue, weight) {
  return currentValue + (nextValue - currentValue) * weight;
}

export function toNumber(value, fallback = 0) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export function sanitizeExperiment(experiment = {}) {
  return {
    group: experiment.group === 'B' ? 'B' : 'A',
    accepted: Boolean(experiment.accepted)
  };
}

function normalizeRiskMeta(player) {
  const previous = player.riskMeta ?? {};
  const fallbackLoss = Math.max(LOSS_BASELINE_MIN, toNumber(player.lossChange24hPercent, LOSS_BASELINE_MIN));
  const fallbackSession = Math.max(
    SESSION_BASELINE_MIN,
    toNumber(player.avgSessionMinutes, SESSION_BASELINE_MIN)
  );

  return {
    baselineLossPct: clamp(toNumber(previous.baselineLossPct, fallbackLoss), LOSS_BASELINE_MIN, 160),
    baselineSessionMinutes: clamp(
      toNumber(previous.baselineSessionMinutes, fallbackSession),
      SESSION_BASELINE_MIN,
      240
    ),
    rollingLossPct: clamp(toNumber(previous.rollingLossPct, toNumber(player.lossChange24hPercent, 0)), -60, 160),
    rollingSessionMinutes: clamp(
      toNumber(previous.rollingSessionMinutes, toNumber(player.avgSessionMinutes, 45)),
      15,
      240
    ),
    atRiskStreak: Math.max(0, Math.trunc(toNumber(previous.atRiskStreak, 0))),
    healthyStreak: Math.max(0, Math.trunc(toNumber(previous.healthyStreak, 0))),
    updates: Math.max(0, Math.trunc(toNumber(previous.updates, 0)))
  };
}

function refreshRiskMeta(player, riskMeta) {
  const healthySignal =
    player.daysSinceLastBet <= 2 &&
    player.lossChange24hPercent < 45 &&
    player.avgSessionMinutes < 120;

  const baselineWeight = healthySignal ? 0.14 : 0.04;

  return {
    ...riskMeta,
    baselineLossPct: clamp(
      ema(riskMeta.baselineLossPct, Math.max(LOSS_BASELINE_MIN, player.lossChange24hPercent), baselineWeight),
      LOSS_BASELINE_MIN,
      160
    ),
    baselineSessionMinutes: clamp(
      ema(
        riskMeta.baselineSessionMinutes,
        Math.max(SESSION_BASELINE_MIN, player.avgSessionMinutes),
        baselineWeight
      ),
      SESSION_BASELINE_MIN,
      240
    ),
    rollingLossPct: clamp(ema(riskMeta.rollingLossPct, player.lossChange24hPercent, 0.22), -60, 160),
    rollingSessionMinutes: clamp(
      ema(riskMeta.rollingSessionMinutes, player.avgSessionMinutes, 0.22),
      15,
      240
    ),
    updates: riskMeta.updates + 1
  };
}

function lossSpikeThreshold(riskMeta) {
  return Math.max(38, riskMeta.baselineLossPct + 12, riskMeta.rollingLossPct + 9);
}

function longSessionThreshold(riskMeta) {
  return Math.max(105, riskMeta.baselineSessionMinutes * 1.35, riskMeta.rollingSessionMinutes * 1.25);
}

function deriveSevereSignals(player) {
  return {
    severeInactivity: player.daysSinceLastBet >= EXTREME_INACTIVITY_DAYS,
    severeLossSpike: player.lossChange24hPercent >= EXTREME_LOSS_PERCENT,
    severeLongSessions: player.avgSessionMinutes >= EXTREME_SESSION_MINUTES
  };
}

export function deriveTriggers(player, riskMeta = null) {
  const activeRiskMeta = riskMeta ?? normalizeRiskMeta(player);
  const severeSignals = deriveSevereSignals(player);

  return {
    inactivity7d: player.daysSinceLastBet >= 7,
    lossSpike24h:
      player.lossChange24hPercent >= lossSpikeThreshold(activeRiskMeta) || severeSignals.severeLossSpike,
    longSessions:
      player.avgSessionMinutes >= longSessionThreshold(activeRiskMeta) || severeSignals.severeLongSessions
  };
}

export function riskScoreFromTriggers(player, triggers, riskMeta = null) {
  const activeRiskMeta = riskMeta ?? normalizeRiskMeta(player);
  const inactivityScore = clamp(((player.daysSinceLastBet - 1) / 12) * 100, 0, 100);
  const lossDelta = player.lossChange24hPercent - activeRiskMeta.baselineLossPct;
  const lossScore = clamp(((lossDelta + 8) / 58) * 100, 0, 100);
  const sessionDelta = player.avgSessionMinutes - activeRiskMeta.baselineSessionMinutes;
  const sessionScore = clamp(((sessionDelta + 10) / 110) * 100, 0, 100);
  const volatilityScore = clamp(
    (
      (Math.abs(player.lossChange24hPercent - activeRiskMeta.rollingLossPct) +
        Math.abs(player.avgSessionMinutes - activeRiskMeta.rollingSessionMinutes) * 0.45) /
      70
    ) * 100,
    0,
    100
  );

  let score = 9 + inactivityScore * 0.34 + lossScore * 0.37 + sessionScore * 0.23 + volatilityScore * 0.06;

  if (triggers.inactivity7d) {
    score += 11;
  }
  if (triggers.lossSpike24h) {
    score += 11;
  }
  if (triggers.longSessions) {
    score += 9;
  }

  const severe = deriveSevereSignals(player);
  if (severe.severeInactivity || severe.severeLossSpike || severe.severeLongSessions) {
    score += 10;
  }

  if (severe.severeInactivity) {
    score = Math.max(score, 74);
  }
  if (severe.severeLossSpike) {
    score = Math.max(score, 78);
  }
  if (severe.severeLongSessions) {
    score = Math.max(score, 70);
  }

  return Math.round(clamp(score, 0, 100));
}

function resolveSegmentState(player, triggers, riskScore, riskMeta) {
  const severe = deriveSevereSignals(player);
  const severeRisk = severe.severeInactivity || severe.severeLossSpike || severe.severeLongSessions;
  const previousSegment = player.segment;
  const warmupWindow = riskMeta.updates <= 1 && !previousSegment;

  const highRiskNow =
    riskScore >= ENTRY_RISK_THRESHOLD ||
    (triggers.inactivity7d && (triggers.lossSpike24h || triggers.longSessions));
  const lowRiskNow =
    riskScore <= EXIT_RISK_THRESHOLD &&
    !triggers.inactivity7d &&
    !triggers.lossSpike24h &&
    !triggers.longSessions;

  let atRiskStreak = riskMeta.atRiskStreak;
  let healthyStreak = riskMeta.healthyStreak;
  let isAtRisk = false;

  if (severeRisk) {
    isAtRisk = true;
    atRiskStreak =
      previousSegment === 'At-risk' ? riskMeta.atRiskStreak + 1 : Math.max(riskMeta.atRiskStreak + 1, 2);
    healthyStreak = 0;
  } else if (previousSegment === 'At-risk') {
    if (lowRiskNow) {
      healthyStreak = riskMeta.healthyStreak + 1;
    } else {
      healthyStreak = 0;
    }

    atRiskStreak = highRiskNow ? riskMeta.atRiskStreak + 1 : Math.max(riskMeta.atRiskStreak - 1, 0);
    isAtRisk = healthyStreak < 3;

    if (!isAtRisk) {
      atRiskStreak = 0;
    }
  } else {
    atRiskStreak = highRiskNow ? riskMeta.atRiskStreak + 1 : 0;
    healthyStreak = lowRiskNow ? riskMeta.healthyStreak + 1 : 0;
    isAtRisk = atRiskStreak >= 2;

    if (warmupWindow && highRiskNow && riskScore >= ENTRY_RISK_THRESHOLD + 6) {
      isAtRisk = true;
      atRiskStreak = Math.max(atRiskStreak, 2);
    }
  }

  if (isAtRisk) {
    return {
      segment: 'At-risk',
      riskMeta: {
        ...riskMeta,
        atRiskStreak,
        healthyStreak
      }
    };
  }

  if (player.netDeposit30d >= 5000 || player.totalBets30d >= 220) {
    return {
      segment: 'VIP',
      riskMeta: {
        ...riskMeta,
        atRiskStreak: 0,
        healthyStreak
      }
    };
  }

  if (player.daysSinceJoined <= 30) {
    return {
      segment: 'New',
      riskMeta: {
        ...riskMeta,
        atRiskStreak: 0,
        healthyStreak
      }
    };
  }

  return {
    segment: 'Active',
    riskMeta: {
      ...riskMeta,
      atRiskStreak: 0,
      healthyStreak
    }
  };
}

export function resolveSegment(player, triggers, riskScore = null, riskMeta = null) {
  const activeRiskMeta = riskMeta ?? normalizeRiskMeta(player);
  const resolvedRiskScore =
    riskScore === null ? riskScoreFromTriggers(player, triggers, activeRiskMeta) : riskScore;

  return resolveSegmentState(player, triggers, resolvedRiskScore, activeRiskMeta).segment;
}

export function resolveCampaign(segment, triggers) {
  if (triggers.lossSpike24h || triggers.longSessions) {
    return CAMPAIGNS.cooldown;
  }

  if (triggers.inactivity7d && segment === 'VIP') {
    return CAMPAIGNS.oddsBoost;
  }

  if (triggers.inactivity7d) {
    return CAMPAIGNS.freebet;
  }

  if (segment === 'New') {
    return CAMPAIGNS.bonus;
  }

  return CAMPAIGNS.loyalty;
}

export function activeTriggerLabels(triggers) {
  return Object.entries(triggers)
    .filter(([, value]) => value)
    .map(([code]) => TRIGGER_LABELS[code])
    .filter(Boolean);
}

export function enrichPlayer(player) {
  const normalized = {
    ...player,
    daysSinceJoined: Math.max(0, toNumber(player.daysSinceJoined, 0)),
    daysSinceLastBet: Math.max(0, toNumber(player.daysSinceLastBet, 0)),
    totalBets30d: Math.max(0, toNumber(player.totalBets30d, 0)),
    lossChange24hPercent: toNumber(player.lossChange24hPercent, 0),
    avgSessionMinutes: Math.max(0, toNumber(player.avgSessionMinutes, 0)),
    netDeposit30d: Math.max(0, toNumber(player.netDeposit30d, 0)),
    experiment: sanitizeExperiment(player.experiment)
  };

  const riskMeta = refreshRiskMeta(normalized, normalizeRiskMeta(normalized));
  const triggers = deriveTriggers(normalized, riskMeta);
  const riskScore = riskScoreFromTriggers(normalized, triggers, riskMeta);
  const segmentState = resolveSegmentState(normalized, triggers, riskScore, riskMeta);
  const campaign = resolveCampaign(segmentState.segment, triggers);

  return {
    ...normalized,
    segment: segmentState.segment,
    triggers,
    campaign,
    riskScore,
    riskMeta: segmentState.riskMeta,
    triggerLabels: activeTriggerLabels(triggers)
  };
}

export function enrichPlayers(players = []) {
  return players.map((player) => enrichPlayer(player));
}

export function buildIntervention(player) {
  const urgent = player.riskScore >= 72;

  return {
    id: `${player.id}:${player.campaign.id}`,
    playerId: player.id,
    playerName: player.name,
    segment: player.segment,
    campaign: player.campaign.label,
    owner: player.campaign.owner,
    riskScore: player.riskScore,
    priority: urgent ? 1 : player.segment === 'VIP' ? 2 : player.segment === 'New' ? 3 : 4,
    status: urgent ? 'Immediate review' : 'Auto-send',
    triggers: player.triggerLabels.length > 0 ? player.triggerLabels : ['Healthy pattern']
  };
}

function formatRate(accepted, total) {
  if (total === 0) {
    return '0%';
  }
  return `${Math.round((accepted / total) * 100)}%`;
}

function rateValue(accepted, total) {
  if (total === 0) {
    return 0;
  }
  return accepted / total;
}

export function buildInterventions(players = []) {
  return players.map((player) => buildIntervention(player)).sort((left, right) => {
    if (left.priority !== right.priority) {
      return left.priority - right.priority;
    }
    return right.riskScore - left.riskScore;
  });
}

export function filterPlayers(players = [], filters = DEFAULT_FILTERS) {
  const search = filters.search.trim().toLowerCase();
  const requiredTrigger = FILTER_TRIGGER_TO_CODE[filters.trigger] ?? null;

  return players
    .filter((player) => {
      const segmentMatch = filters.segment === 'All' || player.segment === filters.segment;
      const triggerMatch = !requiredTrigger || player.triggers[requiredTrigger];
      const searchMatch =
        !search ||
        `${player.name} ${player.market} ${player.campaign.label}`.toLowerCase().includes(search);

      return segmentMatch && triggerMatch && searchMatch;
    })
    .sort((left, right) => {
      const segmentDiff =
        (SEGMENT_ORDER[left.segment] ?? Number.MAX_SAFE_INTEGER) -
        (SEGMENT_ORDER[right.segment] ?? Number.MAX_SAFE_INTEGER);

      if (segmentDiff !== 0) {
        return segmentDiff;
      }

      return right.riskScore - left.riskScore;
    });
}

export function buildKpis(players = []) {
  const totalPlayers = players.length;
  const atRiskPlayers = players.filter((player) => player.segment === 'At-risk').length;
  const retainedPlayers = players.filter((player) => player.totalBets30d >= 8).length;
  const dormantPlayers = players.filter((player) => player.triggers.inactivity7d).length;
  const reactivatedPlayers = players.filter(
    (player) => player.triggers.inactivity7d && player.experiment.accepted
  ).length;
  const acceptedOffers = players.filter((player) => player.experiment.accepted).length;
  const riskyPlayers = players.filter((player) => player.riskScore >= 72).length;

  return {
    totalPlayers,
    atRiskPlayers,
    retention30d: totalPlayers === 0 ? 0 : Math.round((retainedPlayers / totalPlayers) * 100),
    reactivationRate:
      dormantPlayers === 0 ? 0 : Math.round((reactivatedPlayers / dormantPlayers) * 100),
    promoRoi: totalPlayers === 0 ? 0 : Math.round((acceptedOffers / totalPlayers) * 170 - 32),
    riskyTrend: totalPlayers === 0 ? 0 : Math.round((riskyPlayers / totalPlayers) * 100)
  };
}

export function buildExperimentSummary(players = []) {
  const buckets = new Map();

  for (const player of players) {
    const key = player.campaign.id;

    if (!buckets.has(key)) {
      buckets.set(key, {
        campaign: player.campaign.label,
        groupATotal: 0,
        groupAAccepted: 0,
        groupBTotal: 0,
        groupBAccepted: 0
      });
    }

    const bucket = buckets.get(key);

    if (player.experiment.group === 'B') {
      bucket.groupBTotal += 1;
      if (player.experiment.accepted) {
        bucket.groupBAccepted += 1;
      }
    } else {
      bucket.groupATotal += 1;
      if (player.experiment.accepted) {
        bucket.groupAAccepted += 1;
      }
    }
  }

  return [...buckets.values()]
    .map((bucket) => {
      const groupARateValue = rateValue(bucket.groupAAccepted, bucket.groupATotal);
      const groupBRateValue = rateValue(bucket.groupBAccepted, bucket.groupBTotal);
      let winner = 'Tie';

      if (groupARateValue > groupBRateValue) {
        winner = 'Group A';
      } else if (groupBRateValue > groupARateValue) {
        winner = 'Group B';
      }

      return {
        campaign: bucket.campaign,
        groupA: `${bucket.groupAAccepted}/${bucket.groupATotal} (${formatRate(
          bucket.groupAAccepted,
          bucket.groupATotal
        )})`,
        groupB: `${bucket.groupBAccepted}/${bucket.groupBTotal} (${formatRate(
          bucket.groupBAccepted,
          bucket.groupBTotal
        )})`,
        winner
      };
    })
    .sort((left, right) => left.campaign.localeCompare(right.campaign));
}

export function applyActivityUpdateToPlayers(players = [], update = {}) {
  const playerIndex = players.findIndex((player) => player.id === update.playerId);

  if (playerIndex === -1) {
    return players;
  }

  const current = players[playerIndex];
  const merged = {
    ...current,
    daysSinceJoined: toNumber(update.daysSinceJoined, current.daysSinceJoined),
    daysSinceLastBet: toNumber(update.daysSinceLastBet, current.daysSinceLastBet),
    totalBets30d: toNumber(update.totalBets30d, current.totalBets30d),
    lossChange24hPercent: toNumber(update.lossChange24hPercent, current.lossChange24hPercent),
    avgSessionMinutes: toNumber(update.avgSessionMinutes, current.avgSessionMinutes),
    netDeposit30d: toNumber(update.netDeposit30d, current.netDeposit30d),
    experiment: current.experiment
  };

  const nextPlayers = players.slice();
  nextPlayers[playerIndex] = enrichPlayer(merged);
  return nextPlayers;
}
