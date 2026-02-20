<script setup>
const props = defineProps({
  rows: {
    type: Array,
    required: true
  }
});
</script>

<template>
  <section class="experiment-panel">
    <header>
      <p class="panel-label">A/B tracking</p>
      <h2>Campaign performance</h2>
    </header>

    <p v-if="props.rows.length === 0" class="panel-empty">No experiment data.</p>

    <table v-else>
      <thead>
        <tr>
          <th>Campaign</th>
          <th>Group A</th>
          <th>Group B</th>
          <th>Winner</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in props.rows" :key="row.campaign">
          <td>{{ row.campaign }}</td>
          <td>{{ row.groupA }}</td>
          <td>{{ row.groupB }}</td>
          <td>{{ row.winner }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
