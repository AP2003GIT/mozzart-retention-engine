<script setup>
const props = defineProps({
  player: {
    type: Object,
    required: true
  }
});

function segmentClass(segment) {
  return `segment-${segment.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`;
}

function riskClass(score) {
  if (score >= 70) {
    return 'risk-high';
  }
  if (score >= 45) {
    return 'risk-mid';
  }
  return 'risk-low';
}
</script>

<template>
  <article class="player-card" data-testid="player-card">
    <header class="player-head">
      <div>
        <p class="market">{{ props.player.market }}</p>
        <h2>{{ props.player.name }}</h2>
      </div>
      <div class="head-meta">
        <span :class="['segment-pill', segmentClass(props.player.segment)]">
          {{ props.player.segment }}
        </span>
        <p class="risk-line">
          Risk:
          <strong :class="['risk-score', riskClass(props.player.riskScore)]">
            {{ props.player.riskScore }}
          </strong>
        </p>
      </div>
    </header>

    <dl class="metric-grid">
      <div>
        <dt>Last bet</dt>
        <dd>{{ props.player.daysSinceLastBet }}d ago</dd>
      </div>
      <div>
        <dt>Session avg</dt>
        <dd>{{ props.player.avgSessionMinutes }}m</dd>
      </div>
      <div>
        <dt>Loss change</dt>
        <dd>{{ props.player.lossChange24hPercent }}%</dd>
      </div>
      <div>
        <dt>Bets (30d)</dt>
        <dd>{{ props.player.totalBets30d }}</dd>
      </div>
    </dl>

    <div class="trigger-row">
      <p>Triggers</p>
      <ul>
        <li v-if="props.player.triggerLabels.length === 0" class="trigger-chip trigger-safe">
          Healthy pattern
        </li>
        <li
          v-for="trigger in props.player.triggerLabels"
          :key="trigger"
          class="trigger-chip trigger-risk"
        >
          {{ trigger }}
        </li>
      </ul>
    </div>

    <footer class="campaign-box">
      <p class="campaign-label">{{ props.player.campaign.label }}</p>
      <p class="campaign-owner">{{ props.player.campaign.owner }} automation</p>
    </footer>
  </article>
</template>
