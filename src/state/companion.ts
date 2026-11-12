import { useSyncExternalStore } from 'react';
import { EMPTY_SNAPSHOT, type CompanionSnapshot } from '../../modules/pillion-native';
import {
  getCompanionSnapshot,
  setAutoReconnect,
  subscribeCompanionState,
} from '@/data/companion';

let snapshot: CompanionSnapshot = EMPTY_SNAPSHOT;
const listeners = new Set<() => void>();
let started = false;

function emit(): void {
  for (const listener of listeners) {
    listener();
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

export async function initCompanionBridge(autoReconnect: boolean): Promise<void> {
  if (!started) {
    started = true;
    subscribeCompanionState((next) => {
      snapshot = next;
      emit();
    });
  }
  await setAutoReconnect(autoReconnect);
  snapshot = await getCompanionSnapshot();
  emit();
}

export async function refreshCompanionSnapshot(): Promise<CompanionSnapshot> {
  snapshot = await getCompanionSnapshot();
  emit();
  return snapshot;
}

