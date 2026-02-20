<script setup>
const props = defineProps({
  queue: {
    type: Array,
    required: true
  }
});

function statusClass(status) {
  return status === 'Immediate review' ? 'status-alert' : 'status-ok';
}
</script>

<template>
  <section class="queue-panel">
    <header>
      <p class="panel-label">Automation queue</p>
      <h2>CRM + Risk interventions</h2>
    </header>

    <p v-if="props.queue.length === 0" class="panel-empty">Queue is empty.</p>

    <ul v-else class="queue-list">
      <li v-for="item in props.queue" :key="item.id" class="queue-item">
        <div class="queue-item-head">
          <p>{{ item.playerName }}</p>
          <span :class="['status-pill', statusClass(item.status)]">{{ item.status }}</span>
        </div>
        <p class="queue-meta">{{ item.segment }} • {{ item.campaign }} • {{ item.owner }}</p>
        <p class="queue-meta">Priority {{ item.priority }} • Risk {{ item.riskScore }}</p>
      </li>
    </ul>
  </section>
</template>
