import { describe, expect, it, vi } from 'vitest';

vi.mock('../../modules/pillion-native', () => ({
  EMPTY_SNAPSHOT: {
    connectionStatus: 'Disconnected',
    isServerRunning: false,
    isOverlayRunning: false,
    songTitle: 'No Media',
    artistName: 'Unknown',
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
    hasNotificationAccess: true,
    hasOverlayPermission: true,
    hasRuntimePermissions: true,
  },
  PillionNative: null,
}));

vi.mock('react-native', () => ({
  Platform: { OS: 'android', Version: 34 },
}));

import {
  initCompanionBridge,
  registerCompanionRideHandlers,
  resetCompanionBridgeForTests,
  teardownPreviousRelayMode,
} from './companion';
import type { CompanionSnapshot, MirrorNotification } from '../../modules/pillion-native';

const mocks = vi.hoisted(() => ({
  snapshot: {
    connectionStatus: 'Disconnected',
    isServerRunning: false,
    isOverlayRunning: false,
    songTitle: 'No Media',
    artistName: 'Unknown',
    isPlaying: false,
    progressMs: 0,
    durationMs: 0,
    batteryLevel: -1,
    isCallActive: false,
    isCallIncoming: false,
    callerName: '',
    callerNumber: '',
    notifications: [] as MirrorNotification[],
    contacts: [],
    recentCalls: [],
    hasNotificationAccess: true,
    hasOverlayPermission: true,
    hasRuntimePermissions: true,
  } as CompanionSnapshot,
  listeners: new Set<(s: CompanionSnapshot) => void>(),
  setRelayRole: vi.fn(async () => {}),
  setAutoReconnect: vi.fn(async () => {}),
  stopServer: vi.fn(async () => {}),
  disconnect: vi.fn(async () => {}),
}));

vi.mock('@/data/companion', () => ({
  getCompanionSnapshot: vi.fn(async () => mocks.snapshot),
  setCompanionRelayRole: mocks.setRelayRole,
  setAutoReconnect: mocks.setAutoReconnect,
  stopSenderServer: mocks.stopServer,
  disconnectCompanion: mocks.disconnect,
  subscribeCompanionState: (cb: (s: CompanionSnapshot) => void) => {
    mocks.listeners.add(cb);
    return () => mocks.listeners.delete(cb);
  },
}));

describe('companion bridge ride handlers & lifecycle', () => {
  it('dispatches notification deltas and disconnect triggers to ride handlers', async () => {
    resetCompanionBridgeForTests();
    mocks.setRelayRole.mockClear();
    mocks.setAutoReconnect.mockClear();
    mocks.stopServer.mockClear();
    mocks.disconnect.mockClear();

    const onNotifs = vi.fn();
    const onDisconnect = vi.fn();

    registerCompanionRideHandlers({
      onNotificationsAdded: onNotifs,
      onDisconnected: onDisconnect,
    });

    await initCompanionBridge('receiver');
    expect(mocks.setRelayRole).toHaveBeenCalledWith('receiver');
    expect(mocks.setAutoReconnect).toHaveBeenCalledWith(true);

    const connectedSnapshot: CompanionSnapshot = {
      ...mocks.snapshot,
      connectionStatus: 'Connected',
      notifications: [
        {
          key: 'k1',
          packageName: 'com.chat',
          title: 'Alice',
          text: 'Hi',
          receivedAt: 1000,
          canReply: true,
        },
      ],
    };

    for (const listener of mocks.listeners) {
      listener(connectedSnapshot);
    }
    expect(onNotifs).toHaveBeenCalledWith(1);

    const twoNotifsSnapshot: CompanionSnapshot = {
      ...connectedSnapshot,
      notifications: [
        ...connectedSnapshot.notifications,
        {
          key: 'k2',
          packageName: 'com.chat',
          title: 'Bob',
          text: 'Ride safe',
          receivedAt: 2000,
          canReply: false,
        },
      ],
    };
    for (const listener of mocks.listeners) {
      listener(twoNotifsSnapshot);
    }
    expect(onNotifs).toHaveBeenCalledWith(1);

    const droppedSnapshot: CompanionSnapshot = {
      ...twoNotifsSnapshot,
      connectionStatus: 'Disconnected',
    };
    for (const listener of mocks.listeners) {
      listener(droppedSnapshot);
    }
    expect(onDisconnect).toHaveBeenCalled();

    await teardownPreviousRelayMode('sender');
    expect(mocks.stopServer).toHaveBeenCalled();

    await teardownPreviousRelayMode('receiver');
    expect(mocks.setAutoReconnect).toHaveBeenCalledWith(false);
    expect(mocks.disconnect).toHaveBeenCalled();
  });
});
