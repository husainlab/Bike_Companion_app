import { useSyncExternalStore } from 'react';
import { DEFAULT_APP_SETTINGS, type AppSettings, type ThemePreference } from '@/core/types';

export interface AppState {
  booted: boolean;
  settings: AppSettings;
}

let state: AppState = {
  booted: false,
  settings: { ...DEFAULT_APP_SETTINGS },
};

const listeners = new Set<() => void>();
let themeApplier: ((theme: ThemePreference) => void) | null = null;

function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

export function registerThemeApplier(fn: (theme: ThemePreference) => void): void {
  themeApplier = fn;
}

export function getAppState(): AppState {
  return state;
}

export function subscribeAppState(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useAppState(): AppState {
  return useSyncExternalStore(subscribeAppState, getAppState, getAppState);
}

export function setAppStateBooted(settings: AppSettings): AppState {
  state = { booted: true, settings };
  themeApplier?.(settings.theme);
  emit();
  return state;
}

export function setAppSettingsLocal(settings: AppSettings): void {
  state = { ...state, settings };
  themeApplier?.(settings.theme);
  emit();
}

