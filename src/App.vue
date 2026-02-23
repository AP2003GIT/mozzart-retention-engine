<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue';
import KpiStrip from './components/KpiStrip.vue';
import EngineFilters from './components/EngineFilters.vue';
import PlayerBoard from './components/PlayerBoard.vue';
import InterventionQueue from './components/InterventionQueue.vue';
import ExperimentTable from './components/ExperimentTable.vue';
import { useRetentionEngineStore } from './stores/retentionEngine';

const store = useRetentionEngineStore();

onMounted(() => {
  store.init();
  store.connect();
});

onBeforeUnmount(() => {
  store.disconnect();
});

const lastUpdateText = computed(() => {
  if (!store.lastUpdatedAt) {
    return 'waiting for first activity update';
  }

  return `updated at ${new Date(store.lastUpdatedAt).toLocaleTimeString()}`;
});

const streamSourceText = computed(() => {
  if (store.streamSource === 'backend') {
    return 'Backend API';
  }

  if (store.streamSource === 'mock') {
    return 'Local mock feed';
  }

  return 'Not connected';
});

function toggleStream() {
  if (store.isConnected) {
    store.disconnect();
  } else {
    store.connect();
  }
}
</script>

<template>
  <main class="engine-shell">
    <header class="engine-header">
      <div>
        <p class="product-label">MOZZART BET</p>
        <h1>Retention + Responsible Gaming Engine</h1>
        <p class="header-copy">
          Segmentation, risk triggers, and campaign automation in one operator dashboard.
        </p>
      </div>

      <div class="status-card">
        <p>
          Stream:
          <span :class="['status-dot', store.isConnected ? 'status-live' : 'status-paused']">
            {{ store.isConnected ? 'Live' : 'Paused' }}
          </span>
        </p>
        <p class="status-sub">Source: {{ streamSourceText }}</p>
        <p class="status-sub" role="status" aria-live="polite">{{ lastUpdateText }}</p>
        <button
          type="button"
          class="status-btn"
          :aria-pressed="store.isConnected ? 'true' : 'false'"
          @click="toggleStream"
        >
          {{ store.isConnected ? 'Pause feed' : 'Resume feed' }}
        </button>
      </div>
    </header>

    <KpiStrip :kpis="store.kpis" />

    <EngineFilters
      :segments="store.segments"
      :trigger-options="store.triggerOptions"
      :filters="store.filters"
      @update-filters="store.setFilters"
    />

    <section class="dashboard-grid">
      <PlayerBoard :players="store.filteredPlayers" />

      <div class="side-column">
        <InterventionQueue :queue="store.interventionQueue" />
        <ExperimentTable :rows="store.experimentSummary" />
      </div>
    </section>
  </main>
</template>
