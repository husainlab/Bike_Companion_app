import type { PillionStore } from './store';

/**
 * Wraps read-heavy `PillionStore` queries with a single-version cache keyed by
 * `getVersion()`. Any write through `actions/` bumps `dataVersion`, invalidating
 * cached reads automatically.
 */
export function memoStore(store: PillionStore, getVersion: () => number): PillionStore {
  const cache = new Map<string, { version: number; value: Promise<unknown> }>();

  function cached<T>(key: string, loader: () => Promise<T>): Promise<T> {
    const version = getVersion();
    const hit = cache.get(key);
    if (hit && hit.version === version) {
      return hit.value as Promise<T>;
    }
    const promise = loader().catch((err) => {
      cache.delete(key);
      throw err;
    });
    cache.set(key, { version, value: promise });
    return promise;
  }

  return {
    init: () => store.init(),
    getSettings: () => cached('settings', () => store.getSettings()),
    updateSettings: async (patch) => {
      cache.delete('settings');
      return store.updateSettings(patch);
    },
    listPairedDevices: () => cached('paired_devices', () => store.listPairedDevices()),
    upsertPairedDevice: async (device) => {
      cache.delete('paired_devices');
      return store.upsertPairedDevice(device);
    },
    listRideSessions: (limit = 20) =>
      cached(`ride_sessions:${limit}`, () => store.listRideSessions(limit)),
    saveRideSession: async (session) => {
      for (const k of cache.keys()) {
        if (k.startsWith('ride_sessions:')) cache.delete(k);
      }
      return store.saveRideSession(session);
    },
    clearRideSessions: async () => {
      for (const k of cache.keys()) {
        if (k.startsWith('ride_sessions:')) cache.delete(k);
      }
      return store.clearRideSessions();
    },
  };
}

