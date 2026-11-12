import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-native', () => ({
  Platform: { OS: 'android', Version: 34 },
}));

const mocks = vi.hoisted(() => ({
  getSnapshot: vi.fn(async () => ({
    connectionStatus: 'Disconnected',
    isServerRunning: false,
    isOverlayRunning: false,
    songTitle: 'No Media Playing',
    artistName: 'Unknown Artist',
    isPlaying: false,
    progressMs: 0,
    durationMs: 0,
    batteryLevel: -1,
    isCallActive: false,
    isCallIncoming: false,
    callerName: '',
    callerNumber: '',
    notifications: [],
    contacts: [],
    recentCalls: [],
    hasNotificationAccess: false,
    hasOverlayPermission: false,
    hasRuntimePermissions: false,
  })),
  getPairedDevices: vi.fn(async () => [{ name: 'Pocket Phone', address: 'AA:BB:CC:DD:EE:FF' }]),
  setRelayRole: vi.fn(async () => {}),
  sendCommand: vi.fn(async () => {}),
  launchGoogleMaps: vi.fn(async () => true),
  remove: vi.fn(),
  addListener: vi.fn(),
}));

vi.mock('../../modules/pillion-native', () => ({
  EMPTY_SNAPSHOT: {
    connectionStatus: 'Disconnected',
    isServerRunning: false,
    isOverlayRunning: false,
    songTitle: 'No Media Playing',
    artistName: 'Unknown Artist',
    isPlaying: false,
    progressMs: 0,
    durationMs: 0,
    batteryLevel: -1,
    isCallActive: false,
    isCallIncoming: false,
    callerName: '',
    callerNumber: '',
    notifications: [],
    contacts: [],
    recentCalls: [],
    hasNotificationAccess: false,
    hasOverlayPermission: false,
    hasRuntimePermissions: false,
  },
  PillionNative: {
    getSnapshot: mocks.getSnapshot,
    getPairedDevices: mocks.getPairedDevices,
    setRelayRole: mocks.setRelayRole,
    sendCommand: mocks.sendCommand,
    launchGoogleMaps: mocks.launchGoogleMaps,
    addListener: mocks.addListener.mockImplementation(() => ({ remove: mocks.remove })),
  },
}));

import {
  dialContactNumber,
  dismissMirroredNotification,
  getCompanionSnapshot,
  getPairedBluetoothDevices,
  launchLocalGoogleMaps,
  replyToMirroredNotification,
  setCompanionRelayRole,
  subscribeCompanionState,
} from './companion';

describe('companion native data adapter', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('forwards commands with trimmed inputs and guards blank numbers/replies', async () => {
    await setCompanionRelayRole('receiver');
    expect(mocks.setRelayRole).toHaveBeenCalledWith('receiver');

    await dialContactNumber('  +15551234  ');
    await dialContactNumber('   ');
    expect(mocks.sendCommand).toHaveBeenCalledTimes(1);
    expect(mocks.sendCommand).toHaveBeenCalledWith('/call/dial|+15551234');

    await dismissMirroredNotification('key-1');
    await dismissMirroredNotification('');
    expect(mocks.sendCommand).toHaveBeenCalledWith('/notif/dismiss|key-1');

    await replyToMirroredNotification('key-1', ' On my way ');
    await replyToMirroredNotification('key-1', '   ');
    expect(mocks.sendCommand).toHaveBeenCalledWith('/notif/reply|key-1|On my way');

    expect(await getCompanionSnapshot()).toMatchObject({
      connectionStatus: 'Disconnected',
      songTitle: 'No Media Playing',
      batteryLevel: -1,
    });
    expect(await getPairedBluetoothDevices()).toHaveLength(1);
    expect(await launchLocalGoogleMaps(true)).toBe(true);

    const unsub = subscribeCompanionState(() => {});
    unsub();
    expect(mocks.remove).toHaveBeenCalledTimes(1);
  });
});
