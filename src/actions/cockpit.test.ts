import { describe, expect, it } from 'vitest';
import { MemoryStore } from '@/data/memory';
import { memoStore } from '@/data/memo';
import { loadCockpitOverview, loadRideHistory } from '@/queries/cockpit';
import { getDataVersion } from '@/state/dataVersion';
import {
  clearRideHistory,
  completeOnboarding,
  logRideSession,
  recordConnectedDevice,
  setRelayMode,
  setThemePreference,
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

  it('rejects invalid inputs with zod', async () => {
    const store = new MemoryStore();
    await expect(
      recordConnectedDevice(store, { address: '12', name: '' })
    ).rejects.toThrow();
  });
});

