import { useSyncExternalStore } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as Device from 'expo-device';
import { File, Paths } from 'expo-file-system';
import * as IntentLauncher from 'expo-intent-launcher';
import appConfig from '../../app.json';
import { apkFor, isNewer, parseRelease, parseSemver } from '@/lib/release';

const GITHUB_OWNER = 'husainlab';
const GITHUB_REPO = 'Bike_Companion_app';
const APP_DISPLAY_PREFIX = 'Pillion';
const PACKAGE_ID = appConfig.expo.android.package;
const CURRENT_VERSION = appConfig.expo.version;

const STORAGE_ENABLED_KEY = 'pillion.update_check_enabled';
const STORAGE_LAST_CHECK_KEY = 'pillion.update_last_checked_at';
const CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const REQUEST_TIMEOUT_MS = 15_000;
const GRANT_READ_URI_PERMISSION = 1;
const FLAG_ACTIVITY_NEW_TASK = 268_435_456;

export interface AvailableUpdate {
  version: string;
  apk: string;
  page: string;
  notes: string;
}

export interface UpdateState {
  enabled: boolean;
  checking: boolean;
  available: AvailableUpdate | null;
  progress: number | null;
  checkError: string | null;
  currentVersion: string;
  lastCheckedAt: number | null;
}

let state: UpdateState = {
  enabled: true,
  checking: false,
  available: null,
  progress: null,
  checkError: null,
  currentVersion: CURRENT_VERSION,
  lastCheckedAt: null,
};

let dismissedInSession = false;
const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) {
    listener();
  }
}

export function getUpdate(): UpdateState {
  return state;
}

export function subscribeUpdate(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useUpdate(): UpdateState {
  return useSyncExternalStore(subscribeUpdate, getUpdate, getUpdate);
}

export function resetUpdateStateForTests(): void {
  dismissedInSession = false;
  state = {
    enabled: true,
    checking: false,
    available: null,
    progress: null,
    checkError: null,
    currentVersion: CURRENT_VERSION,
    lastCheckedAt: null,
  };
  emit();
}

export async function loadUpdatePreferences(): Promise<void> {
  try {
    const [enabledRaw, lastCheckedRaw] = await Promise.all([
      AsyncStorage.getItem(STORAGE_ENABLED_KEY),
      AsyncStorage.getItem(STORAGE_LAST_CHECK_KEY),
    ]);
    const enabled = enabledRaw === null ? true : enabledRaw === 'true';
    const lastCheckedAt = lastCheckedRaw ? Number(lastCheckedRaw) : null;
    state = {
      ...state,
      enabled,
      lastCheckedAt: Number.isFinite(lastCheckedAt) ? lastCheckedAt : null,
    };
    emit();
  } catch {
    // Non-fatal if storage is unavailable
  }
}

export async function setUpdateCheck(on: boolean): Promise<void> {
  state = {
    ...state,
    enabled: on,
    available: on ? state.available : null,
    checkError: null,
  };
  emit();
  try {
    await AsyncStorage.setItem(STORAGE_ENABLED_KEY, on ? 'true' : 'false');
  } catch {
    // Ignore storage write error
  }
}

export function putOffUpdate(): void {
  dismissedInSession = true;
  state = {
    ...state,
    available: null,
  };
  emit();
}

async function fetchGitHubJson(url: string): Promise<unknown> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(url, {
      headers: {
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`GitHub API returned HTTP ${response.status}`);
    }
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function checkLatestViaTagsFallback(): Promise<AvailableUpdate | null> {
  const tagsUrl = `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/tags?per_page=1`;
  const raw = await fetchGitHubJson(tagsUrl);
  if (!Array.isArray(raw) || raw.length === 0) return null;
  const first = raw[0] as { name?: unknown };
  if (typeof first.name !== 'string') return null;

  const parsed = parseSemver(first.name);
  if (!parsed) return null;
  const version = `${parsed[0]}.${parsed[1]}.${parsed[2]}`;
  if (!isNewer(version, CURRENT_VERSION)) return null;

  return {
    version,
    apk: `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/raw/main/${APP_DISPLAY_PREFIX}-release.apk`,
    page: `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/tag/${first.name}`,
    notes: '',
  };
}

export async function checkForAppUpdate(force = false): Promise<AvailableUpdate | null> {
  if (!state.enabled && !force) return null;
  if (dismissedInSession && !force) return null;

  const now = Date.now();
  if (
    !force &&
    state.lastCheckedAt !== null &&
    now - state.lastCheckedAt < CHECK_INTERVAL_MS
  ) {
    return state.available;
  }

  state = {
    ...state,
    checking: true,
    checkError: null,
  };
  emit();

  try {
    const releasesUrl = `https://api.github.com/repos/${GITHUB_OWNER}/${GITHUB_REPO}/releases?per_page=1`;
    const payload = await fetchGitHubJson(releasesUrl);

    let candidate: AvailableUpdate | null = null;
    if (Array.isArray(payload) && payload.length > 0) {
      const parsed = parseRelease(payload[0], APP_DISPLAY_PREFIX);
      if (parsed && isNewer(parsed.version, CURRENT_VERSION)) {
        const apk = apkFor(parsed, Device.supportedCpuArchitectures);
        if (apk) {
          candidate = {
            version: parsed.version,
            apk,
            page:
              parsed.page ||
              `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/releases/tag/${parsed.tag}`,
            notes: parsed.notes,
          };
        }
      }
    }

    if (!candidate) {
      candidate = await checkLatestViaTagsFallback();
    }

    if (force) {
      dismissedInSession = false;
    }

    state = {
      ...state,
      checking: false,
      available: candidate,
      checkError: null,
      lastCheckedAt: now,
    };
    emit();

    await AsyncStorage.setItem(STORAGE_LAST_CHECK_KEY, String(now)).catch(() => {});
    return candidate;
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Unable to check for updates';
    state = {
      ...state,
      checking: false,
      checkError: message,
    };
    emit();
    return null;
  }
}

export async function maybeCheckForAppUpdate(): Promise<void> {
  await loadUpdatePreferences();
  await checkForAppUpdate(false);
}

export async function installUpdate(): Promise<void> {
  const target = state.available;
  if (!target) {
    throw new Error('No update available to install');
  }

  state = {
    ...state,
    progress: 0,
    checkError: null,
  };
  emit();

  const cachedApk = new File(Paths.cache, `${APP_DISPLAY_PREFIX}-update.apk`);
  try {
    if (cachedApk.exists) {
      cachedApk.delete();
    }

    const task = File.createDownloadTask(target.apk, cachedApk, {
      onProgress: ({ bytesWritten, totalBytes }) => {
        if (totalBytes > 0) {
          state = {
            ...state,
            progress: Math.max(0, Math.min(1, bytesWritten / totalBytes)),
          };
          emit();
        }
      },
    });

    const downloaded = (await task.downloadAsync()) ?? cachedApk;
    state = {
      ...state,
      progress: 1,
    };
    emit();

    try {
      await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
        data: downloaded.contentUri,
        type: 'application/vnd.android.package-archive',
        flags: GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK,
      });
    } catch (launchErr) {
      await IntentLauncher.startActivityAsync(
        IntentLauncher.ActivityAction.MANAGE_UNKNOWN_APP_SOURCES,
        {
          data: `package:${PACKAGE_ID}`,
        }
      ).catch(() => {});
      throw launchErr;
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Update installation failed';
    state = {
      ...state,
      progress: null,
      checkError: message,
    };
    emit();
    throw err;
  }
}
