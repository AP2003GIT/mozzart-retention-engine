<script setup>
const props = defineProps({
  segments: {
    type: Array,
    required: true
  },
  triggerOptions: {
    type: Array,
    required: true
  },
  filters: {
    type: Object,
    required: true
  }
});

const emit = defineEmits(['update-filters']);

function onSegmentChange(event) {
  emit('update-filters', {
    segment: event.target.value
  });
}

function onTriggerChange(event) {
  emit('update-filters', {
    trigger: event.target.value
  });
}

function onSearchInput(event) {
  emit('update-filters', {
    search: event.target.value
  });
}
</script>

<template>
  <section class="filter-row" aria-label="Engine filters">
    <label class="field" for="segment-filter">
      <span>Segment</span>
      <select
        id="segment-filter"
        :value="props.filters.segment"
        aria-label="Filter by segment"
        @change="onSegmentChange"
      >
        <option v-for="segment in props.segments" :key="segment" :value="segment">
          {{ segment }}
        </option>
      </select>
    </label>

    <label class="field" for="trigger-filter">
      <span>Trigger</span>
      <select
        id="trigger-filter"
        :value="props.filters.trigger"
        aria-label="Filter by trigger"
        @change="onTriggerChange"
      >
        <option v-for="trigger in props.triggerOptions" :key="trigger" :value="trigger">
          {{ trigger }}
        </option>
      </select>
    </label>

    <label class="field field-wide" for="player-search">
      <span>Player search</span>
      <input
        id="player-search"
        type="search"
        placeholder="Search by name, market, or campaign"
        :value="props.filters.search"
        aria-label="Search players"
        @input="onSearchInput"
      />
    </label>
  </section>
</template>
