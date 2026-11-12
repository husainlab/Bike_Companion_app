import {
  DEFAULT_APP_SETTINGS,
  type AppSettings,
  type PairedDeviceRecord,
  type RideSessionRecord,
} from '@/core/types';
import type { PillionStore } from './store';

export class MemoryStore implements PillionStore {
  private settings: AppSettings;
  private devices = new Map<string, PairedDeviceRecord>();
  private sessions = new Map<string, RideSessionRecord>();

  constructor(initial?: Partial<AppSettings>) {
    this.settings = { ...DEFAULT_APP_SETTINGS, ...initial };
  }

  async init(): Promise<void> {}

  async getSettings(): Promise<AppSettings> {
    return { ...this.settings };
  }

  async updateSettings(patch: Partial<AppSettings>): Promise<AppSettings> {
    this.settings = { ...this.settings, ...patch };
    return { ...this.settings };
  }

  async listPairedDevices(): Promise<PairedDeviceRecord[]> {
    return Array.from(this.devices.values()).sort(
      (a, b) => b.lastConnectedAt - a.lastConnectedAt
    );
  }

  async upsertPairedDevice(device: PairedDeviceRecord): Promise<void> {
    this.devices.set(device.address, { ...device });
  }

  async listRideSessions(limit = 20): Promise<RideSessionRecord[]> {
    return Array.from(this.sessions.values())
      .sort((a, b) => b.startedAt - a.startedAt)
      .slice(0, limit);
  }

  async saveRideSession(session: RideSessionRecord): Promise<void> {
    this.sessions.set(session.id, { ...session });
  }

  async clearRideSessions(): Promise<void> {
    this.sessions.clear();
  }
}

