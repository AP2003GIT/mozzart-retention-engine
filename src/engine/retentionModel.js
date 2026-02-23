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

export function deriveTriggers(player) {
  return {
    inactivity7d: player.daysSinceLastBet >= 7,
    lossSpike24h: player.lossChange24hPercent >= 45,
    longSessions: player.avgSessionMinutes >= 120
  };
}

export function resolveSegment(player, triggers) {
  if (triggers.inactivity7d || triggers.lossSpike24h || triggers.longSessions) {
    return 'At-risk';
  }

  if (player.netDeposit30d >= 5000 || player.totalBets30d >= 220) {
    return 'VIP';
  }

  if (player.daysSinceJoined <= 30) {
    return 'New';
  }

  return 'Active';
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

export function riskScoreFromTriggers(player, triggers) {
  let score = 8;

  if (triggers.inactivity7d) {
    score += 42;
  }

  if (triggers.lossSpike24h) {
    score += 36;
  }

  if (triggers.longSessions) {
    score += 24;
  }

  if (player.lossChange24hPercent > 70) {
    score += 10;
  }

  return Math.min(100, score);
}

export function activeTriggerLabels(triggers) {
  return Object.entries(triggers)
    .filter(([, value]) => value)
    .map(([code]) => TRIGGER_LABELS[code]);
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

  const triggers = deriveTriggers(normalized);
  const segment = resolveSegment(normalized, triggers);
  const campaign = resolveCampaign(segment, triggers);

  return {
    ...normalized,
    segment,
    triggers,
    campaign,
    riskScore: riskScoreFromTriggers(normalized, triggers),
    triggerLabels: activeTriggerLabels(triggers)
  };
}

export function enrichPlayers(players = []) {
  return players.map((player) => enrichPlayer(player));
}

export function buildIntervention(player) {
  const urgent = player.riskScore >= 70;

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
        !search || `${player.name} ${player.market} ${player.campaign.label}`.toLowerCase().includes(search);

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
  const riskyPlayers = players.filter((player) => player.riskScore >= 70).length;

  return {
    totalPlayers,
    atRiskPlayers,
    retention30d: totalPlayers === 0 ? 0 : Math.round((retainedPlayers / totalPlayers) * 100),
    reactivationRate: dormantPlayers === 0 ? 0 : Math.round((reactivatedPlayers / dormantPlayers) * 100),
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
