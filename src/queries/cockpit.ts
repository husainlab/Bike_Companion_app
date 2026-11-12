import type {
  AppSettings,
  PairedDeviceRecord,
  RideSessionRecord,
} from '@/core/types';
import type { PillionStore } from '@/data/store';

export interface QueryCtx {
  store: PillionStore;
  version: number;
}

export interface CockpitOverviewVM {
  settings: AppSettings;
  recentDevices: PairedDeviceRecord[];
  recentRides: RideSessionRecord[];
}

export async function loadCockpitOverview(ctx: QueryCtx): Promise<CockpitOverviewVM> {
  const [settings, recentDevices, recentRides] = await Promise.all([
    ctx.store.getSettings(),
    ctx.store.listPairedDevices(),
    ctx.store.listRideSessions(10),
  ]);
  return {
    settings,
    recentDevices,
    recentRides,
  };
}

export async function loadRideHistory(ctx: QueryCtx): Promise<RideSessionRecord[]> {
  return ctx.store.listRideSessions(50);
}

