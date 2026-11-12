import { useSyncExternalStore } from 'react';

let version = 0;
const listeners = new Set<() => void>();

export function getDataVersion(): number {
  return version;
}

export function dataChanged(): void {
  version += 1;
  for (const listener of listeners) {
    listener();
  }
}

export function subscribeDataVersion(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useDataVersion(): number {
  return useSyncExternalStore(subscribeDataVersion, getDataVersion, getDataVersion);
}

