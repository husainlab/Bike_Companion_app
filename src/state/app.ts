import { colorScheme } from 'nativewind';
import type { ThemePreference } from '@/core/types';
import { SqliteStore } from '@/data/db';
import { memoStore } from '@/data/memo';
import type { PillionStore } from '@/data/store';
import { getDataVersion } from './dataVersion';
import {
  getAppState,
  registerThemeApplier,
  setAppSettingsLocal,
  setAppStateBooted,
  subscribeAppState,
  useAppState,
  type AppState,
} from './settingsAtom';

export type { AppState };
export { getAppState, setAppSettingsLocal, subscribeAppState, useAppState };

const baseStore = new SqliteStore();
const store: PillionStore = memoStore(baseStore, getDataVersion);
let bootPromise: Promise<AppState> | null = null;

export function getStore(): PillionStore {
  return store;
}

export function applyTheme(theme: ThemePreference): void {
  try {
    colorScheme.set(theme);
  } catch {
    // No-op in headless Node test environment
  }
}

registerThemeApplier(applyTheme);

export async function bootApp(): Promise<AppState> {
  const current = getAppState();
  if (current.booted) return current;
  if (bootPromise) return bootPromise;

  bootPromise = (async () => {
    await store.init();
    const settings = await store.getSettings();
    return setAppStateBooted(settings);
  })().catch((err) => {
    bootPromise = null;
    throw err;
  });

  return bootPromise;
}

export function resetAppStateForTests(): void {
  bootPromise = null;
}
