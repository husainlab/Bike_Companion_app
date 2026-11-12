import { describe, expect, it } from 'vitest';
import { MemoryStore } from '@/data/memory';
import { memoStore } from '@/data/memo';
import { loadCockpitOverview, loadRideHistory } from '@/queries/cockpit';
import { getDataVersion } from '@/state/dataVersion';
import {
  clearRideHistory,
  completeOnboarding,
  endActiveRideSession,
  incrementActiveRideNotifications,
  logRideSession,
  recordConnectedDevice,
  setRelayMode,
  setThemePreference,
  startRideSession,
} from './cockpit';

describe('cockpit actions and queries with MemoryStore', () => {
  it('validates and persists settings, devices, and ride logs while bumping dataVersion', async () => {
    const store = memoStore(new MemoryStore(), getDataVersion);
    const v0 = getDataVersion();

    await completeOnboarding(store, 'sender');
    expect(getDataVersion()).toBeGreaterThan(v0);

    await setRelayMode(store, 'receiver');
    await setThemePreference(store, 'dark');
    await recordConnectedDevice(store, { address: 'AA:BB:CC:DD:EE:FF', name: 'Pixel Pocket' }, 1000);
    await logRideSession(store, {
      id: 'ride-1',
      mode: 'receiver',
      deviceName: 'Pixel Pocket',
      startedAt: 1000,
      endedAt: 2000,
      notificationsRelayed: 4,
    });

    const vm = await loadCockpitOverview({ store, version: getDataVersion() });
    expect(vm.settings.onboardingComplete).toBe(true);
    expect(vm.settings.relayMode).toBe('receiver');
    expect(vm.settings.theme).toBe('dark');
    expect(vm.recentDevices).toHaveLength(1);
    expect(vm.recentRides).toHaveLength(1);

    await clearRideHistory(store);
    expect(await loadRideHistory({ store, version: getDataVersion() })).toHaveLength(0);
  });

  it('manages ride session lifecycles and increments notification tallies', async () => {
    const store = new MemoryStore();
    const id1 = await startRideSession(store, 'receiver', 'Pixel Pocket', 1000);
    expect(id1).toContain('ride-1000');

    await incrementActiveRideNotifications(store, 3);
    const activeRides = await store.listRideSessions(5);
    expect(activeRides[0].notificationsRelayed).toBe(3);
    expect(activeRides[0].endedAt).toBeNull();

    // Starting a new session automatically closes any lingering unended ride
    await startRideSession(store, 'receiver', 'Pixel Pocket 2', 5000);
    const twoRides = await store.listRideSessions(5);
    expect(twoRides).toHaveLength(2);
    expect(twoRides[1].endedAt).toBe(5000);
    expect(twoRides[0].endedAt).toBeNull();

    await endActiveRideSession(store, 7000);
    const ended = await store.listRideSessions(5);
    expect(ended[0].endedAt).toBe(7000);
  });

  it('rejects invalid inputs with zod', async () => {
    const store = new MemoryStore();
    await expect(
      recordConnectedDevice(store, { address: '12', name: '' })
    ).rejects.toThrow();
  });
});

