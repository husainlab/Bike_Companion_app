import { colorScheme } from 'nativewind';
import { DEFAULT_APP_SETTINGS, type AppSettings, type ThemePreference } from '@/core/types';
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
const BOOT_TIMEOUT_MS = 2000;

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
    const bootWork = async (): Promise<AppSettings> => {
      await store.init();
      return await store.getSettings();
    };

    let timer: ReturnType<typeof setTimeout> | null = null;
    const timeoutPromise = new Promise<AppSettings>((resolve) => {
      timer = setTimeout(() => {
        resolve({ ...DEFAULT_APP_SETTINGS });
      }, BOOT_TIMEOUT_MS);
    });

    try {
      const settings = await Promise.race([bootWork(), timeoutPromise]);
      if (timer) clearTimeout(timer);
      return setAppStateBooted(settings);
    } catch (err) {
      if (timer) clearTimeout(timer);
      throw err;
    }
  })().catch((err) => {
    bootPromise = null;
    throw err;
  });

  return bootPromise;
}

export function resetAppStateForTests(): void {
  bootPromise = null;
}
