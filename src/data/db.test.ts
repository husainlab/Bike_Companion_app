import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SqliteStore } from './db';

const mocks = vi.hoisted(() => {
  const execCalls: { sql: string; inTx: boolean }[] = [];
  const runCalls: { sql: string; params: unknown[]; inTx: boolean }[] = [];
  let inTx = false;
  let storedSettings = {
    relay_mode: 'receiver',
    theme: 'dark',
    keep_awake_on_ride: 1,
    auto_start_overlay: 0,
    auto_volume_enabled: 0,
    onboarding_complete: 0,
  };
  let storedDevices: { address: string; name: string; last_connected_at: number }[] = [];
  let storedRides: {
    id: string;
    mode: string;
    device_name: string;
    started_at: number;
    ended_at: number | null;
    notifications_relayed: number;
  }[] = [];

  const mockTxn = {
    execAsync: vi.fn(async (sql: string) => {
      execCalls.push({ sql, inTx });
      if (sql.includes('DELETE FROM ride_sessions')) {
        storedRides = [];
      }
    }),
    runAsync: vi.fn(async (sql: string, params: unknown[] = []) => {
      runCalls.push({ sql, params, inTx });
      if (sql.includes('UPDATE app_settings')) {
        storedSettings = {
          relay_mode: String(params[0]),
          theme: String(params[1]),
          keep_awake_on_ride: Number(params[2]),
          auto_start_overlay: Number(params[3]),
          auto_volume_enabled: Number(params[4]),
          onboarding_complete: Number(params[5]),
        };
      } else if (sql.includes('INSERT INTO paired_devices')) {
        const address = String(params[0]);
        const rest = storedDevices.filter((d) => d.address !== address);
        storedDevices = [
          { address, name: String(params[1]), last_connected_at: Number(params[2]) },
          ...rest,
        ];
      } else if (sql.includes('INSERT INTO ride_sessions')) {
        const id = String(params[0]);
        const rest = storedRides.filter((r) => r.id !== id);
        storedRides = [
          {
            id,
            mode: String(params[1]),
            device_name: String(params[2]),
            started_at: Number(params[3]),
            ended_at: params[4] === null ? null : Number(params[4]),
            notifications_relayed: Number(params[5]),
          },
          ...rest,
        ];
      } else if (sql.includes('DELETE FROM ride_sessions')) {
        storedRides = [];
      }
    }),
  };

  const mockDb = {
    execAsync: vi.fn(async (sql: string) => {
      if (inTx && sql.includes('journal_mode')) {
        throw new Error('cannot change into wal mode from within a transaction');
      }
      execCalls.push({ sql, inTx });
    }),
    withExclusiveTransactionAsync: vi.fn(async (cb: (txn: typeof mockTxn) => Promise<void>) => {
      inTx = true;
      try {
        await cb(mockTxn);
      } finally {
        inTx = false;
      }
    }) as any,
    getFirstAsync: vi.fn(async () => storedSettings),
    getAllAsync: vi.fn(async (sql: string) => {
      if (sql.includes('FROM paired_devices')) return storedDevices;
      if (sql.includes('FROM ride_sessions')) return storedRides;
      return [];
    }),
  };

  return {
    execCalls,
    runCalls,
    mockDb,
    reset: () => {
      execCalls.length = 0;
      runCalls.length = 0;
      inTx = false;
      storedSettings = {
        relay_mode: 'receiver',
        theme: 'dark',
        keep_awake_on_ride: 1,
        auto_start_overlay: 0,
        auto_volume_enabled: 0,
        onboarding_complete: 0,
      };
      storedDevices = [];
      storedRides = [];
      mockDb.execAsync.mockClear();
      mockDb.withExclusiveTransactionAsync.mockClear();
    },
  };
});

vi.mock('expo-sqlite', () => ({
  openDatabaseAsync: vi.fn(async () => mocks.mockDb),
}));

describe('SqliteStore persistence & initialization safety', () => {
  beforeEach(() => {
    mocks.reset();
  });

  it('sets WAL pragma outside transactions and deduplicates concurrent init() calls', async () => {
    const store = new SqliteStore();
    await Promise.all([store.init(), store.init(), store.init()]);

    expect(mocks.mockDb.withExclusiveTransactionAsync).toHaveBeenCalledTimes(1);
    const walCall = mocks.execCalls.find((c) => c.sql.includes('journal_mode = WAL'));
    expect(walCall).toBeDefined();
    expect(walCall?.inTx).toBe(false);
  });

  it('persists settings, paired devices, and ride sessions', async () => {
    const store = new SqliteStore();
    const updated = await store.updateSettings({
      onboardingComplete: true,
      relayMode: 'sender',
      theme: 'light',
    });
    expect(updated.onboardingComplete).toBe(true);
    expect(updated.relayMode).toBe('sender');

    await store.upsertPairedDevice({
      address: 'AA:BB:CC:DD:EE:FF',
      name: 'Pocket Phone',
      lastConnectedAt: 1234,
    });
    expect(await store.listPairedDevices()).toHaveLength(1);

    await store.saveRideSession({
      id: 'ride-1',
      mode: 'receiver',
      deviceName: 'Pocket Phone',
      startedAt: 1000,
      endedAt: null,
      notificationsRelayed: 2,
    });
    expect((await store.listRideSessions())[0].notificationsRelayed).toBe(2);

    await store.clearRideSessions();
    expect(await store.listRideSessions()).toHaveLength(0);
  });
});
