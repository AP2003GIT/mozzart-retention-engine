<script setup>
const props = defineProps({
  kpis: {
    type: Object,
    required: true
  }
});
</script>

<template>
  <section class="kpi-strip" aria-label="Portfolio KPIs">
    <article class="kpi-card">
      <p>Total players</p>
      <strong>{{ props.kpis.totalPlayers }}</strong>
    </article>

    <article class="kpi-card">
      <p>At-risk players</p>
      <strong>{{ props.kpis.atRiskPlayers }}</strong>
    </article>

    <article class="kpi-card">
      <p>30-day retention</p>
      <strong>{{ props.kpis.retention30d }}%</strong>
    </article>

    <article class="kpi-card">
      <p>Reactivation rate</p>
      <strong>{{ props.kpis.reactivationRate }}%</strong>
    </article>

    <article class="kpi-card">
      <p>Promo ROI</p>
      <strong>{{ props.kpis.promoRoi }}%</strong>
    </article>

    <article class="kpi-card">
      <p>Risky behavior trend</p>
      <strong>{{ props.kpis.riskyTrend }}%</strong>
    </article>
  </section>
</template>
