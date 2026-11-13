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
  const updated = await patchSettings(store, { keepAwakeOnRide: parsed });
  try {
    const { setCompanionKeepScreenAwake } = await import('@/data/companion');
    await setCompanionKeepScreenAwake(parsed);
  } catch {}
  return updated;
}

export async function setAutoStartOverlay(
  store: PillionStore,
  autoStartOverlay: boolean
): Promise<AppSettings> {
  const parsed = z.boolean().parse(autoStartOverlay);
  return patchSettings(store, { autoStartOverlay: parsed });
}

export async function setAutoVolumeEnabled(
  store: PillionStore,
  autoVolumeEnabled: boolean
): Promise<AppSettings> {
  const parsed = z.boolean().parse(autoVolumeEnabled);
  return patchSettings(store, { autoVolumeEnabled: parsed });
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
  deviceName: string,
  nowMs: number = Date.now()
): Promise<string> {
  const existing = await store.listRideSessions(1);
  if (existing.length > 0 && existing[0].endedAt === null) {
    await store.saveRideSession({
      ...existing[0],
      endedAt: Math.max(existing[0].startedAt, nowMs),
    });
  }

  const id = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? `ride-${nowMs}-${crypto.randomUUID()}`
    : `ride-${nowMs}-${Math.floor(Math.random() * 10_000)}`;
  await logRideSession(store, {
    id,
    mode,
    deviceName,
    startedAt: nowMs,
    endedAt: null,
    notificationsRelayed: 0,
  });
  return id;
}

export async function incrementActiveRideNotifications(
  store: PillionStore,
  delta: number = 1
): Promise<void> {
  const safeDelta = z.number().int().positive().parse(delta);
  const existing = await store.listRideSessions(1);
  if (existing.length === 0 || existing[0].endedAt !== null) return;
  await logRideSession(store, {
    ...existing[0],
    notificationsRelayed: existing[0].notificationsRelayed + safeDelta,
  });
}

export async function endActiveRideSession(
  store: PillionStore,
  endedAt: number = Date.now()
): Promise<void> {
  const existing = await store.listRideSessions(1);
  if (existing.length === 0 || existing[0].endedAt !== null) return;
  const safeEnd = Math.max(existing[0].startedAt, endedAt);
  await logRideSession(store, {
    ...existing[0],
    endedAt: safeEnd,
  });
}

export async function clearRideHistory(store: PillionStore): Promise<void> {
  await store.clearRideSessions();
  dataChanged();
}
