import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useRetentionEngineStore } from '../src/stores/retentionEngine';

describe('retention engine store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('classifies seeded players into expected segments and triggers', () => {
    const store = useRetentionEngineStore();
    store.init();

    const inactive = store.players.find((player) => player.id === 'p-1002');
    const vip = store.players.find((player) => player.id === 'p-1009');
    const newcomer = store.players.find((player) => player.id === 'p-1012');

    expect(inactive.segment).toBe('Active');
    expect(inactive.triggers.inactivity7d).toBe(true);
    expect(vip.segment).toBe('VIP');
    expect(newcomer.segment).toBe('New');
  });

  it('builds and sorts intervention queue by priority and risk', () => {
    const store = useRetentionEngineStore();
    store.init();

    expect(store.interventionQueue.length).toBeGreaterThan(0);

    for (let index = 1; index < store.interventionQueue.length; index += 1) {
      const previous = store.interventionQueue[index - 1];
      const current = store.interventionQueue[index];

      expect(current.priority).toBeGreaterThanOrEqual(previous.priority);

      if (current.priority === previous.priority) {
        expect(current.riskScore).toBeLessThanOrEqual(previous.riskScore);
      }
    }
  });

  it('recomputes campaign and risk state when activity updates arrive', () => {
    const store = useRetentionEngineStore();
    store.init();

    store.applyActivityUpdate({
      playerId: 'p-1001',
      daysSinceLastBet: 10,
      lossChange24hPercent: 66,
      avgSessionMinutes: 134
    });

    const updated = store.players.find((player) => player.id === 'p-1001');

    expect(updated.segment).toBe('At-risk');
    expect(updated.campaign.label).toBe('Cooldown suggestion');
    expect(updated.riskScore).toBeGreaterThanOrEqual(70);
    expect(store.lastUpdatedAt).toBeTruthy();
  });

  it('exposes A/B experiment summary rows', () => {
    const store = useRetentionEngineStore();
    store.init();

    expect(store.experimentSummary.length).toBeGreaterThan(0);
    expect(store.experimentSummary[0]).toHaveProperty('campaign');
    expect(store.experimentSummary[0]).toHaveProperty('groupA');
    expect(store.experimentSummary[0]).toHaveProperty('groupB');
  });
});
