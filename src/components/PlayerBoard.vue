<script setup>
import PlayerCard from './PlayerCard.vue';

const props = defineProps({
  players: {
    type: Array,
    required: true
  }
});
</script>

<template>
  <section class="player-board" aria-live="polite" aria-label="Player cards">
    <p v-if="props.players.length === 0" class="empty-state">
      No players match the current filters.
    </p>

    <PlayerCard v-for="player in props.players" :key="player.id" :player="player" />
  </section>
</template>
