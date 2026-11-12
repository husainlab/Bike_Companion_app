import type {
  AppSettings,
  PairedDeviceRecord,
  RideSessionRecord,
} from '@/core/types';

export interface PillionStore {
  init(): Promise<void>;
  getSettings(): Promise<AppSettings>;
  updateSettings(patch: Partial<AppSettings>): Promise<AppSettings>;
  listPairedDevices(): Promise<PairedDeviceRecord[]>;
  upsertPairedDevice(device: PairedDeviceRecord): Promise<void>;
  listRideSessions(limit?: number): Promise<RideSessionRecord[]>;
  saveRideSession(session: RideSessionRecord): Promise<void>;
  clearRideSessions(): Promise<void>;
}

