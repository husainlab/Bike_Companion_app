import { useSyncExternalStore } from 'react';
import { EMPTY_SNAPSHOT, type CompanionSnapshot } from '../../modules/pillion-native';
import {
  clearSessionMissedCalls,
  disconnectCompanion,
  getCompanionSnapshot,
  sendCockpitCommand,
  setAutoReconnect,
  setCompanionRelayRole,
  stopSenderServer,
  subscribeCompanionState,
} from '@/data/companion';
import { evaluateSpeedVolumeTransition } from '@/core/speedVolume';

export type { CompanionSnapshot };

export interface CompanionRideHandlers {
  onNotificationsAdded?: (delta: number) => void;
  onDisconnected?: () => void;
  onAutoStartOverlay?: () => void;
}

export interface VolumeHudState {
  visible: boolean;
  percent: number;
  isAuto: boolean;
  updatedAt: number;
}

let snapshot: CompanionSnapshot = EMPTY_SNAPSHOT;
const listeners = new Set<() => void>();
let started = false;
let rideHandlers: CompanionRideHandlers = {};
let prevStatus: string = EMPTY_SNAPSHOT.connectionStatus;
let seenNotifKeys = new Set<string>();
let activeRelayMode: 'receiver' | 'sender' = 'receiver';
let autoStartOverlayEnabled = false;
let autoVolumeEnabled = false;
let currentSpeedVolumeBracket = 0;
let lastSpeedVolumeTransitionMs = 0;
let prevVolumePercent = -1;

let volumeHudState: VolumeHudState = {
  visible: false,
  percent: 70,
  isAuto: false,
  updatedAt: 0,
};
const volumeHudListeners = new Set<() => void>();
let volumeHudTimer: ReturnType<typeof setTimeout> | null = null;

function emitVolumeHud(): void {
  for (const listener of volumeHudListeners) {
    listener();
  }
}

export function showVolumeHud(percent: number, isAuto: boolean = false): void {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  volumeHudState = {
    visible: true,
    percent: clamped,
    isAuto,
    updatedAt: Date.now(),
  };
  emitVolumeHud();
  if (volumeHudTimer) {
    clearTimeout(volumeHudTimer);
  }
  volumeHudTimer = setTimeout(() => {
    volumeHudState = {
      ...volumeHudState,
      visible: false,
    };
    emitVolumeHud();
  }, 2500);
}

export function hideVolumeHud(): void {
  if (volumeHudTimer) {
    clearTimeout(volumeHudTimer);
    volumeHudTimer = null;
  }
  if (!volumeHudState.visible) return;
  volumeHudState = {
    ...volumeHudState,
    visible: false,
  };
  emitVolumeHud();
}

export function getVolumeHudState(): VolumeHudState {
  return volumeHudState;
}

export function subscribeVolumeHud(listener: () => void): () => void {
  volumeHudListeners.add(listener);
  return () => {
    volumeHudListeners.delete(listener);
  };
}

export function useVolumeHud(): VolumeHudState {
  return useSyncExternalStore(subscribeVolumeHud, getVolumeHudState, getVolumeHudState);
}

export function setCompanionAutoVolumeEnabled(enabled: boolean): void {
  autoVolumeEnabled = enabled;
  if (!enabled) {
    currentSpeedVolumeBracket = 0;
  }
}

export function getCompanionAutoVolumeBracket(): number {
  return currentSpeedVolumeBracket;
}

export function shouldAutoStartOverlayOnConnect(
  previousStatus: string,
  next: Pick<CompanionSnapshot, 'connectionStatus' | 'isOverlayRunning'>,
  autoStartEnabled: boolean,
  relayMode: 'receiver' | 'sender'
): boolean {
  if (!autoStartEnabled || relayMode !== 'receiver') return false;
  const justConnected = previousStatus !== 'Connected' && next.connectionStatus === 'Connected';
  return justConnected && !next.isOverlayRunning;
}

export function setCompanionAutoStartOverlay(enabled: boolean): void {
  autoStartOverlayEnabled = enabled;
}

function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

export function registerCompanionRideHandlers(handlers: CompanionRideHandlers): void {
  rideHandlers = handlers;
}

export function resetCompanionBridgeForTests(): void {
  snapshot = EMPTY_SNAPSHOT;
  started = false;
  rideHandlers = {};
  prevStatus = EMPTY_SNAPSHOT.connectionStatus;
  seenNotifKeys = new Set<string>();
  activeRelayMode = 'receiver';
  autoStartOverlayEnabled = false;
  autoVolumeEnabled = false;
  currentSpeedVolumeBracket = 0;
  lastSpeedVolumeTransitionMs = 0;
  prevVolumePercent = -1;
  if (volumeHudTimer) {
    clearTimeout(volumeHudTimer);
    volumeHudTimer = null;
  }
  volumeHudState = {
    visible: false,
    percent: 70,
    isAuto: false,
    updatedAt: 0,
  };
}

function handleSnapshotTransition(next: CompanionSnapshot): void {
  const wasConnected = prevStatus === 'Connected';
  const isConnected = next.connectionStatus === 'Connected';
  const shouldAutoStart = shouldAutoStartOverlayOnConnect(
    prevStatus,
    next,
    autoStartOverlayEnabled,
    activeRelayMode
  );
  prevStatus = next.connectionStatus;

  const currentVol = next.volumePercent;
  if (prevVolumePercent !== -1 && currentVol !== prevVolumePercent) {
    showVolumeHud(currentVol, false);
  }
  prevVolumePercent = currentVol;

  if (shouldAutoStart) {
    rideHandlers.onAutoStartOverlay?.();
  }

  if (wasConnected && !isConnected) {
    seenNotifKeys.clear();
    rideHandlers.onDisconnected?.();
    return;
  }

  if (isConnected) {
    if (autoVolumeEnabled && activeRelayMode === 'receiver') {
      const now = Date.now();
      const result = evaluateSpeedVolumeTransition({
        speedKmh: next.currentSpeedKmh,
        currentBracket: currentSpeedVolumeBracket,
        lastTransitionAtMs: lastSpeedVolumeTransitionMs,
        nowMs: now,
        isCallActive: next.isCallActive,
        enabled: autoVolumeEnabled,
      });
      if (result.shouldUpdateBracket) {
        currentSpeedVolumeBracket = result.targetBracket;
        lastSpeedVolumeTransitionMs = now;
        const delta = result.action === 'volume_up' ? result.steps * 7 : -result.steps * 7;
        const estPct = Math.max(0, Math.min(100, (next.volumePercent || 70) + delta));
        showVolumeHud(estPct, true);
        if (result.action === 'volume_up') {
          for (let i = 0; i < result.steps; i++) {
            void sendCockpitCommand('/volume/up');
          }
        } else if (result.action === 'volume_down') {
          for (let i = 0; i < result.steps; i++) {
            void sendCockpitCommand('/volume/down');
          }
        }
      }
    }

    let added = 0;
    const nextKeys = new Set<string>();
    for (const n of next.notifications) {
      nextKeys.add(n.key);
      if (!seenNotifKeys.has(n.key)) {
        added += 1;
      }
    }
    seenNotifKeys = nextKeys;
    if (added > 0) {
      rideHandlers.onNotificationsAdded?.(added);
    }
  } else {
    seenNotifKeys = new Set(next.notifications.map((n) => n.key));
  }
}

export function getCompanionState(): CompanionSnapshot {
  return snapshot;
}

export function subscribeCompanion(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useCompanionState(): CompanionSnapshot {
  return useSyncExternalStore(subscribeCompanion, getCompanionState, getCompanionState);
}

export async function initCompanionBridge(
  relayMode: 'receiver' | 'sender',
  autoStartOverlay?: boolean,
  autoVolume?: boolean
): Promise<void> {
  activeRelayMode = relayMode;
  if (typeof autoStartOverlay === 'boolean') {
    autoStartOverlayEnabled = autoStartOverlay;
  }
  if (typeof autoVolume === 'boolean') {
    autoVolumeEnabled = autoVolume;
  }
  const isReceiver = relayMode === 'receiver';
  if (!started) {
    started = true;
    subscribeCompanionState((next) => {
      snapshot = next;
      handleSnapshotTransition(next);
      emit();
    });
  }
  await setCompanionRelayRole(relayMode).catch(() => {});
  await setAutoReconnect(isReceiver).catch(() => {});
  snapshot = await getCompanionSnapshot();
  handleSnapshotTransition(snapshot);
  emit();
}

export async function teardownPreviousRelayMode(previousMode: 'receiver' | 'sender'): Promise<void> {
  if (previousMode === 'sender') {
    await stopSenderServer().catch(() => {});
  } else {
    await setAutoReconnect(false).catch(() => {});
    await disconnectCompanion().catch(() => {});
  }
  snapshot = await getCompanionSnapshot();
  emit();
}

export async function refreshCompanionSnapshot(): Promise<CompanionSnapshot> {
  snapshot = await getCompanionSnapshot();
  emit();
  return snapshot;
}

export async function clearCompanionMissedCalls(): Promise<void> {
  snapshot = {
    ...snapshot,
    sessionMissedCalls: 0,
  };
  emit();
  await clearSessionMissedCalls().catch(() => {});
}

