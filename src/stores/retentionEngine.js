import { defineStore } from 'pinia';
import { seedPlayers } from '../data/seedPlayers';
import { startMockPlayerActivityStream } from '../services/mockPlayerActivityStream';
import {
  DEFAULT_FILTERS,
  TRIGGER_LABELS,
  enrichPlayer,
  enrichPlayers,
  buildInterventions,
  filterPlayers,
  buildKpis,
  buildExperimentSummary,
  applyActivityUpdateToPlayers
} from '../engine/retentionModel';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

function apiUrl(path) {
  return `${API_BASE_URL}${path}`;
}

async function fetchWithTimeout(url, timeoutMs = 1200) {
  if (typeof fetch !== 'function') {
    throw new Error('fetch-unavailable');
  }

  const controller = new AbortController();
  const timeoutId = globalThis.setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      cache: 'no-store',
      signal: controller.signal
    });

    return response;
  } finally {
    globalThis.clearTimeout(timeoutId);
  }
}

async function canReachBackend() {
  try {
    const response = await fetchWithTimeout(apiUrl('/api/health'), 900);
    return response.ok;
  } catch {
    return false;
  }
}

export const useRetentionEngineStore = defineStore('retentionEngine', {
  state: () => ({
    players: [],
    filters: { ...DEFAULT_FILTERS },
    interventions: [],
    isConnected: false,
    lastUpdatedAt: null,
    stopStream: null,
    streamSource: 'none'
  }),
  getters: {
    segments(state) {
      return ['All', ...new Set(state.players.map((player) => player.segment))];
    },
    triggerOptions() {
      return ['All', ...Object.values(TRIGGER_LABELS)];
    },
    filteredPlayers(state) {
      return filterPlayers(state.players, state.filters);
    },
    kpis(state) {
      return buildKpis(state.players);
    },
    interventionQueue(state) {
      return state.interventions.slice(0, 10);
    },
    experimentSummary(state) {
      return buildExperimentSummary(state.players);
    }
  },
  actions: {
    init() {
      this.players = enrichPlayers(seedPlayers);
      this.filters = { ...DEFAULT_FILTERS };
      this.lastUpdatedAt = null;
      this.streamSource = 'none';
      this.runAutomation();
    },
    async connect() {
      if (this.stopStream) {
        return;
      }

      const connectedToBackend = await this.connectBackendStream();
      if (connectedToBackend) {
        return;
      }

      this.connectMockStream();
    },
    async connectBackendStream() {
      if (typeof EventSource !== 'function') {
        return false;
      }

      const backendAvailable = await canReachBackend();
      if (!backendAvailable) {
        return false;
      }

      try {
        await this.hydrateFromBackend();

        const stream = new EventSource(apiUrl('/api/stream'));
        const stop = () => {
          stream.close();
          this.isConnected = false;
        };

        stream.onopen = () => {
          this.isConnected = true;
          this.streamSource = 'backend';
        };

        stream.onmessage = (event) => {
          try {
            const payload = JSON.parse(event.data);

            if (payload.type === 'snapshot' && Array.isArray(payload.players)) {
              this.players = payload.players.map((player) => enrichPlayer(player));
              this.lastUpdatedAt = payload.lastUpdatedAt ?? new Date().toISOString();
              this.runAutomation();
              return;
            }

            if (payload.type === 'activity' && payload.update) {
              this.applyActivityUpdate(payload.update);
            }
          } catch {
            // Ignore malformed stream events and keep the connection alive.
          }
        };

        stream.onerror = () => {
          stop();
          if (this.stopStream === stop) {
            this.stopStream = null;
          }
          this.connectMockStream();
        };

        this.stopStream = stop;
        return true;
      } catch {
        return false;
      }
    },
    connectMockStream() {
      if (this.stopStream) {
        return;
      }

      this.isConnected = true;
      this.streamSource = 'mock';
      this.stopStream = startMockPlayerActivityStream({
        players: this.players,
        onUpdate: (update) => this.applyActivityUpdate(update),
        onDisconnect: () => {
          this.isConnected = false;
        }
      });
    },
    disconnect() {
      if (this.stopStream) {
        this.stopStream();
        this.stopStream = null;
      }
      this.isConnected = false;
      this.streamSource = 'none';
    },
    setFilters(partialFilters) {
      this.filters = {
        ...this.filters,
        ...partialFilters
      };

      if (!this.segments.includes(this.filters.segment)) {
        this.filters.segment = 'All';
      }

      if (!this.triggerOptions.includes(this.filters.trigger)) {
        this.filters.trigger = 'All';
      }
    },
    applyActivityUpdate(update) {
      const nextPlayers = applyActivityUpdateToPlayers(this.players, update);

      if (nextPlayers === this.players) {
        return;
      }

      this.players = nextPlayers;
      this.lastUpdatedAt = new Date().toISOString();
      this.runAutomation();
    },
    runAutomation() {
      this.interventions = buildInterventions(this.players);
    },
    async hydrateFromBackend() {
      const response = await fetchWithTimeout(apiUrl('/api/state'));

      if (!response.ok) {
        throw new Error(`state-fetch-failed:${response.status}`);
      }

      const payload = await response.json();

      if (Array.isArray(payload.players)) {
        this.players = payload.players.map((player) => enrichPlayer(player));
      }

      this.lastUpdatedAt = payload.lastUpdatedAt ?? this.lastUpdatedAt;
      this.runAutomation();
    }
  }
});
