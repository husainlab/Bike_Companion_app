export type RelayMode = 'receiver' | 'sender';

export type ThemePreference = 'dark' | 'light';

export interface PairedDeviceRecord {
  address: string;
  name: string;
  lastConnectedAt: number;
}

export interface RideSessionRecord {
  id: string;
  mode: RelayMode;
  deviceName: string;
  startedAt: number;
  endedAt: number | null;
  notificationsRelayed: number;
}

export interface AppSettings {
  relayMode: RelayMode;
  theme: ThemePreference;
  keepAwakeOnRide: boolean;
  autoStartOverlay: boolean;
  onboardingComplete: boolean;
}

export const DEFAULT_APP_SETTINGS: AppSettings = {
  relayMode: 'receiver',
  theme: 'dark',
  keepAwakeOnRide: true,
  autoStartOverlay: false,
  onboardingComplete: false,
};

