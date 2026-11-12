import { z } from 'zod';
import type { AppSettings, RelayMode, ThemePreference } from '@/core/types';
import type { PillionStore } from '@/data/store';
import { setAppSettingsLocal } from '@/state/settingsAtom';
import { dataChanged } from '@/state/dataVersion';

const relayModeSchema = z.enum(['receiver', 'sender']);
const themeSchema = z.enum(['dark', 'light']);

const deviceSchema = z.object({
  address: z.string().min(4),
  name: z.string().min(1),
});

const rideSchema = z.object({
  id: z.string().min(1),
  mode: relayModeSchema,
  deviceName: z.string().min(1),
  startedAt: z.number().int().positive(),
  endedAt: z.number().int().positive().nullable(),
  notificationsRelayed: z.number().int().min(0),
});

async function patchSettings(
  store: PillionStore,
  patch: Partial<AppSettings>
): Promise<AppSettings> {
  const updated = await store.updateSettings(patch);
  setAppSettingsLocal(updated);
  dataChanged();
  return updated;
}

export async function setRelayMode(
  store: PillionStore,
  mode: RelayMode
): Promise<AppSettings> {
  const parsed = relayModeSchema.parse(mode);
  return patchSettings(store, { relayMode: parsed });
}

export async function setThemePreference(
  store: PillionStore,
  theme: ThemePreference
): Promise<AppSettings> {
  const parsed = themeSchema.parse(theme);
  return patchSettings(store, { theme: parsed });
}

export async function setKeepAwakeOnRide(
  store: PillionStore,
  keepAwakeOnRide: boolean
): Promise<AppSettings> {
  const parsed = z.boolean().parse(keepAwakeOnRide);
  return patchSettings(store, { keepAwakeOnRide: parsed });
}

export async function setAutoStartOverlay(
  store: PillionStore,
  autoStartOverlay: boolean
): Promise<AppSettings> {
  const parsed = z.boolean().parse(autoStartOverlay);
  return patchSettings(store, { autoStartOverlay: parsed });
}

export async function completeOnboarding(
  store: PillionStore,
  initialMode?: RelayMode
): Promise<AppSettings> {
  const mode = initialMode ? relayModeSchema.parse(initialMode) : undefined;
  return patchSettings(store, {
    onboardingComplete: true,
    ...(mode ? { relayMode: mode } : {}),
  });
}

export async function recordConnectedDevice(
  store: PillionStore,
  input: { address: string; name: string },
  nowMs: number = Date.now()
): Promise<void> {
  const parsed = deviceSchema.parse(input);
  await store.upsertPairedDevice({
    address: parsed.address,
    name: parsed.name,
    lastConnectedAt: nowMs,
  });
  dataChanged();
}

export async function logRideSession(
  store: PillionStore,
  input: z.input<typeof rideSchema>
): Promise<void> {
  const parsed = rideSchema.parse(input);
  await store.saveRideSession(parsed);
  dataChanged();
}

export async function startRideSession(
  store: PillionStore,
  mode: RelayMode,
  deviceName: string
): Promise<void> {
  const now = Date.now();
  await logRideSession(store, {
    id: `ride-${now}`,
    mode,
    deviceName,
    startedAt: now,
    endedAt: null,
    notificationsRelayed: 0,
  });
}

export async function clearRideHistory(store: PillionStore): Promise<void> {
  await store.clearRideSessions();
  dataChanged();
}
