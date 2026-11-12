import * as SQLite from 'expo-sqlite';
import {
  DEFAULT_APP_SETTINGS,
  type AppSettings,
  type PairedDeviceRecord,
  type RideSessionRecord,
} from '@/core/types';
import type { PillionStore } from './store';

const DB_NAME = 'pillion.db';

export class SqliteStore implements PillionStore {
  private dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;
  private initPromise: Promise<void> | null = null;
  private initialized = false;

  private getDb(): Promise<SQLite.SQLiteDatabase> {
    if (!this.dbPromise) {
      this.dbPromise = SQLite.openDatabaseAsync(DB_NAME);
    }
    return this.dbPromise;
  }

  async init(): Promise<void> {
    if (this.initialized) return;
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      const db = await this.getDb();
      // PRAGMA journal_mode = WAL cannot execute inside a transaction in SQLite.
      await db.execAsync(
        'PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA busy_timeout = 5000;'
      );
      await db.withExclusiveTransactionAsync(async (txn) => {
        await txn.execAsync(`
          CREATE TABLE IF NOT EXISTS app_settings (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            relay_mode TEXT NOT NULL,
            theme TEXT NOT NULL,
            keep_awake_on_ride INTEGER NOT NULL,
            auto_start_overlay INTEGER NOT NULL,
            onboarding_complete INTEGER NOT NULL
          );
          CREATE TABLE IF NOT EXISTS paired_devices (
            address TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            last_connected_at INTEGER NOT NULL
          );
          CREATE TABLE IF NOT EXISTS ride_sessions (
            id TEXT PRIMARY KEY,
            mode TEXT NOT NULL,
            device_name TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            ended_at INTEGER,
            notifications_relayed INTEGER NOT NULL
          );
        `);
        await txn.runAsync(
          `INSERT OR IGNORE INTO app_settings (id, relay_mode, theme, keep_awake_on_ride, auto_start_overlay, onboarding_complete)
           VALUES (1, ?, ?, ?, ?, ?)`,
          [
            DEFAULT_APP_SETTINGS.relayMode,
            DEFAULT_APP_SETTINGS.theme,
            DEFAULT_APP_SETTINGS.keepAwakeOnRide ? 1 : 0,
            DEFAULT_APP_SETTINGS.autoStartOverlay ? 1 : 0,
            DEFAULT_APP_SETTINGS.onboardingComplete ? 1 : 0,
          ]
        );
      });
      this.initialized = true;
    })().catch((err) => {
      this.initPromise = null;
      throw err;
    });

    return this.initPromise;
  }

  async getSettings(): Promise<AppSettings> {
    await this.init();
    const db = await this.getDb();
    const row = await db.getFirstAsync<{
      relay_mode: string;
      theme: string;
      keep_awake_on_ride: number;
      auto_start_overlay: number;
      onboarding_complete: number;
    }>('SELECT * FROM app_settings WHERE id = 1');

    if (!row) return { ...DEFAULT_APP_SETTINGS };
    return {
      relayMode: row.relay_mode === 'sender' ? 'sender' : 'receiver',
      theme: row.theme === 'light' ? 'light' : 'dark',
      keepAwakeOnRide: row.keep_awake_on_ride === 1,
      autoStartOverlay: row.auto_start_overlay === 1,
      onboardingComplete: row.onboarding_complete === 1,
    };
  }

  async updateSettings(patch: Partial<AppSettings>): Promise<AppSettings> {
    const current = await this.getSettings();
    const next: AppSettings = { ...current, ...patch };
    const db = await this.getDb();
    await db.withExclusiveTransactionAsync(async (txn) => {
      await txn.runAsync(
        `UPDATE app_settings
         SET relay_mode = ?, theme = ?, keep_awake_on_ride = ?, auto_start_overlay = ?, onboarding_complete = ?
         WHERE id = 1`,
        [
          next.relayMode,
          next.theme,
          next.keepAwakeOnRide ? 1 : 0,
          next.autoStartOverlay ? 1 : 0,
          next.onboardingComplete ? 1 : 0,
        ]
      );
    });
    return next;
  }

  async listPairedDevices(): Promise<PairedDeviceRecord[]> {
    await this.init();
    const db = await this.getDb();
    const rows = await db.getAllAsync<{
      address: string;
      name: string;
      last_connected_at: number;
    }>('SELECT address, name, last_connected_at FROM paired_devices ORDER BY last_connected_at DESC');

    return rows.map((r) => ({
      address: r.address,
      name: r.name,
      lastConnectedAt: r.last_connected_at,
    }));
  }

  async upsertPairedDevice(device: PairedDeviceRecord): Promise<void> {
    await this.init();
    const db = await this.getDb();
    await db.withExclusiveTransactionAsync(async (txn) => {
      await txn.runAsync(
        `INSERT INTO paired_devices (address, name, last_connected_at)
         VALUES (?, ?, ?)
         ON CONFLICT(address) DO UPDATE SET name = excluded.name, last_connected_at = excluded.last_connected_at`,
        [device.address, device.name, device.lastConnectedAt]
      );
    });
  }

  async listRideSessions(limit = 20): Promise<RideSessionRecord[]> {
    await this.init();
    const db = await this.getDb();
    const rows = await db.getAllAsync<{
      id: string;
      mode: string;
      device_name: string;
      started_at: number;
      ended_at: number | null;
      notifications_relayed: number;
    }>(
      'SELECT id, mode, device_name, started_at, ended_at, notifications_relayed FROM ride_sessions ORDER BY started_at DESC LIMIT ?',
      [limit]
    );
    return rows.map((r) => ({
      id: r.id,
      mode: r.mode === 'sender' ? 'sender' : 'receiver',
      deviceName: r.device_name,
      startedAt: r.started_at,
      endedAt: r.ended_at,
      notificationsRelayed: r.notifications_relayed,
    }));
  }

  async saveRideSession(session: RideSessionRecord): Promise<void> {
    await this.init();
    const db = await this.getDb();
    await db.withExclusiveTransactionAsync(async (txn) => {
      await txn.runAsync(
        `INSERT INTO ride_sessions (id, mode, device_name, started_at, ended_at, notifications_relayed)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET ended_at = excluded.ended_at, notifications_relayed = excluded.notifications_relayed`,
        [
          session.id,
          session.mode,
          session.deviceName,
          session.startedAt,
          session.endedAt,
          session.notificationsRelayed,
        ]
      );
    });
  }

  async clearRideSessions(): Promise<void> {
    await this.init();
    const db = await this.getDb();
    await db.withExclusiveTransactionAsync(async (txn) => {
      await txn.execAsync('DELETE FROM ride_sessions');
    });
  }
}

