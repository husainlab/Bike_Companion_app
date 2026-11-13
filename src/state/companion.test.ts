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
    volumePercent: 70,
    batteryLevel: -1,
    batteryTempC: -1,
    currentSpeedKmh: 0,
    isCallActive: false,
    isCallIncoming: false,
    isCallMuted: false,
    callStartedAtMs: 0,
    callerName: '',
    callerNumber: '',
    sessionMissedCalls: 0,
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
  clearCompanionMissedCalls,
  getVolumeHudState,
  hideVolumeHud,
  initCompanionBridge,
  registerCompanionRideHandlers,
  resetCompanionBridgeForTests,
  setCompanionAutoStartOverlay,
  setCompanionAutoVolumeEnabled,
  shouldAutoStartOverlayOnConnect,
  showVolumeHud,
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
    volumePercent: 70,
    batteryLevel: -1,
    batteryTempC: -1,
    currentSpeedKmh: 0,
    isCallActive: false,
    isCallIncoming: false,
    isCallMuted: false,
    callStartedAtMs: 0,
    callerName: '',
    callerNumber: '',
    sessionMissedCalls: 0,
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
  sendCommand: vi.fn(async (_path: string) => {}),
}));

vi.mock('@/data/companion', () => ({
  getCompanionSnapshot: vi.fn(async () => mocks.snapshot),
  setCompanionRelayRole: mocks.setRelayRole,
  setAutoReconnect: mocks.setAutoReconnect,
  stopSenderServer: mocks.stopServer,
  disconnectCompanion: mocks.disconnect,
  sendCockpitCommand: mocks.sendCommand,
  clearSessionMissedCalls: vi.fn(async () => {
    await mocks.sendCommand('/call/clear_missed');
  }),
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

  it('evaluates shouldAutoStartOverlayOnConnect predicate deterministically', () => {
    expect(
      shouldAutoStartOverlayOnConnect('Disconnected', { connectionStatus: 'Connected', isOverlayRunning: false }, false, 'receiver')
    ).toBe(false);
    expect(
      shouldAutoStartOverlayOnConnect('Disconnected', { connectionStatus: 'Connected', isOverlayRunning: false }, true, 'sender')
    ).toBe(false);
    expect(
      shouldAutoStartOverlayOnConnect('Connected', { connectionStatus: 'Connected', isOverlayRunning: false }, true, 'receiver')
    ).toBe(false);
    expect(
      shouldAutoStartOverlayOnConnect('Disconnected', { connectionStatus: 'Connecting...', isOverlayRunning: false }, true, 'receiver')
    ).toBe(false);
    expect(
      shouldAutoStartOverlayOnConnect('Disconnected', { connectionStatus: 'Connected', isOverlayRunning: true }, true, 'receiver')
    ).toBe(false);
    expect(
      shouldAutoStartOverlayOnConnect('Disconnected', { connectionStatus: 'Connected', isOverlayRunning: false }, true, 'receiver')
    ).toBe(true);
  });

  it('triggers onAutoStartOverlay when reconnecting in receiver mode with auto-start enabled', async () => {
    resetCompanionBridgeForTests();
    const onAutoStart = vi.fn();
    registerCompanionRideHandlers({ onAutoStartOverlay: onAutoStart });

    await initCompanionBridge('receiver', true);

    const connectedSnapshot: CompanionSnapshot = {
      ...mocks.snapshot,
      connectionStatus: 'Connected',
      isOverlayRunning: false,
    };

    for (const listener of mocks.listeners) {
      listener(connectedSnapshot);
    }
    expect(onAutoStart).toHaveBeenCalledTimes(1);

    // Dynamic toggle off stops subsequent auto-starts
    setCompanionAutoStartOverlay(false);
    for (const listener of mocks.listeners) {
      listener({ ...connectedSnapshot, connectionStatus: 'Connecting...' });
      listener(connectedSnapshot);
    }
    expect(onAutoStart).toHaveBeenCalledTimes(1);
  });

  it('adjusts volume when speed transitions hold steady and auto-volume is enabled', async () => {
    resetCompanionBridgeForTests();
    mocks.sendCommand.mockClear();

    await initCompanionBridge('receiver', false, true);

    const connectedSnapshot: CompanionSnapshot = {
      ...mocks.snapshot,
      connectionStatus: 'Connected',
      currentSpeedKmh: 0,
    };

    for (const listener of mocks.listeners) {
      listener(connectedSnapshot);
    }
    expect(mocks.sendCommand).not.toHaveBeenCalled();

    // Now speed increases to 65 km/h (Bracket 2: +2 steps) after steady interval
    const highwaySnapshot: CompanionSnapshot = {
      ...connectedSnapshot,
      currentSpeedKmh: 65,
    };

    for (const listener of mocks.listeners) {
      listener(highwaySnapshot);
    }
    expect(mocks.sendCommand).toHaveBeenCalledWith('/volume/up');
    expect(mocks.sendCommand).toHaveBeenCalledTimes(2);

    // Dynamic toggle off stops adjustments
    mocks.sendCommand.mockClear();
    setCompanionAutoVolumeEnabled(false);
    for (const listener of mocks.listeners) {
      listener({ ...highwaySnapshot, currentSpeedKmh: 10 });
    }
    expect(mocks.sendCommand).not.toHaveBeenCalled();
  });

  it('manages temporary volume HUD display and 2.5s auto-hide timer', () => {
    vi.useFakeTimers();
    try {
      resetCompanionBridgeForTests();
      expect(getVolumeHudState().visible).toBe(false);

      showVolumeHud(85, false);
      expect(getVolumeHudState()).toMatchObject({
        visible: true,
        percent: 85,
        isAuto: false,
      });

      // Advance 1 second - still visible
      vi.advanceTimersByTime(1000);
      expect(getVolumeHudState().visible).toBe(true);

      // Advance remaining 1.5 seconds - auto-dismisses
      vi.advanceTimersByTime(1500);
      expect(getVolumeHudState().visible).toBe(false);

      // Manual hide works
      showVolumeHud(50, true);
      expect(getVolumeHudState().visible).toBe(true);
      expect(getVolumeHudState().isAuto).toBe(true);
      hideVolumeHud();
      expect(getVolumeHudState().visible).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('triggers volume HUD when volumePercent in snapshot changes', async () => {
    resetCompanionBridgeForTests();
    await initCompanionBridge('receiver');

    const snap1: CompanionSnapshot = {
      ...mocks.snapshot,
      connectionStatus: 'Connected',
      volumePercent: 70,
    };
    for (const listener of mocks.listeners) {
      listener(snap1);
    }
    // Same volume as initial snapshot does not trigger HUD
    expect(getVolumeHudState().visible).toBe(false);

    // Snapshot has volume change (e.g. from pocket phone or button)
    const snap2: CompanionSnapshot = {
      ...snap1,
      volumePercent: 85,
    };
    for (const listener of mocks.listeners) {
      listener(snap2);
    }
    expect(getVolumeHudState()).toMatchObject({
      visible: true,
      percent: 85,
      isAuto: false,
    });
  });

  it('clears session missed calls and transmits command to pocket phone', async () => {
    resetCompanionBridgeForTests();
    mocks.sendCommand.mockClear();

    await clearCompanionMissedCalls();
    expect(mocks.sendCommand).toHaveBeenCalledWith('/call/clear_missed');
  });
});
