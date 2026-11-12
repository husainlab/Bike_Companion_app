import { useSyncExternalStore } from 'react';
import { EMPTY_SNAPSHOT, type CompanionSnapshot } from '../../modules/pillion-native';
import {
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

