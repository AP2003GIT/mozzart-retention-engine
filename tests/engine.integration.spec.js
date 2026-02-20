import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

vi.mock('../src/services/mockPlayerActivityStream', () => ({
  startMockPlayerActivityStream: () => () => {}
}));

import App from '../src/App.vue';
import { useRetentionEngineStore } from '../src/stores/retentionEngine';

describe('retention engine integration', () => {
  beforeEach(() => {
    const pinia = createPinia();
    setActivePinia(pinia);
  });

  it('filters player cards by search term', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);

    const wrapper = mount(App, {
      global: {
        plugins: [pinia]
      }
    });

    const store = useRetentionEngineStore();
    await wrapper.vm.$nextTick();

    expect(store.players.length).toBeGreaterThan(0);

    const input = wrapper.get('input[aria-label="Search players"]');
    await input.setValue('Milan');

    expect(store.filteredPlayers.length).toBe(1);
    expect(store.filteredPlayers[0].name).toContain('Milan');
    expect(wrapper.findAll('[data-testid="player-card"]').length).toBe(1);
  });

  it('filters to at-risk segment through the segment control', async () => {
    const pinia = createPinia();
    setActivePinia(pinia);

    const wrapper = mount(App, {
      global: {
        plugins: [pinia]
      }
    });

    const store = useRetentionEngineStore();
    await wrapper.vm.$nextTick();

    const select = wrapper.get('select[aria-label="Filter by segment"]');
    await select.setValue('At-risk');

    expect(store.filteredPlayers.length).toBeGreaterThan(0);
    expect(store.filteredPlayers.every((player) => player.segment === 'At-risk')).toBe(true);
  });
});
