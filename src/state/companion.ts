import { useSyncExternalStore } from 'react';
import { EMPTY_SNAPSHOT, type CompanionSnapshot } from '../../modules/pillion-native';
import {
  disconnectCompanion,
  getCompanionSnapshot,
  setAutoReconnect,
  setCompanionRelayRole,
  stopSenderServer,
  subscribeCompanionState,
} from '@/data/companion';

export interface CompanionRideHandlers {
  onNotificationsAdded?: (delta: number) => void;
  onDisconnected?: () => void;
}

let snapshot: CompanionSnapshot = EMPTY_SNAPSHOT;
const listeners = new Set<() => void>();
let started = false;
let rideHandlers: CompanionRideHandlers = {};
let prevStatus: string = EMPTY_SNAPSHOT.connectionStatus;
let seenNotifKeys = new Set<string>();

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
}

function handleSnapshotTransition(next: CompanionSnapshot): void {
  const wasConnected = prevStatus === 'Connected';
  const isConnected = next.connectionStatus === 'Connected';
  prevStatus = next.connectionStatus;

  if (wasConnected && !isConnected) {
    seenNotifKeys.clear();
    rideHandlers.onDisconnected?.();
    return;
  }

  if (isConnected) {
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

export async function initCompanionBridge(relayMode: 'receiver' | 'sender'): Promise<void> {
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

