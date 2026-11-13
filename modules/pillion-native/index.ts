import { requireOptionalNativeModule } from 'expo-modules-core';

export type ConnectionState =
  | 'Disconnected'
  | 'Waiting for connection...'
  | 'Connecting...'
  | 'Auto-reconnecting...'
  | 'Connected'
  | 'Server Stopped'
  | 'Server Error'
  | 'Server failed'
  | 'Permission denied';

export interface PairedDevice {
  name: string;
  address: string;
}

export interface MirrorNotification {
  key: string;
  packageName: string;
  title: string;
  text: string;
  receivedAt: number;
  canReply: boolean;
  messages?: string[];
}

export interface SyncedContact {
  id: string;
  name: string;
  phoneNumber: string;
  isStarred: boolean;
}

export interface RecentCallEntry {
  id: string;
  name: string;
  phoneNumber: string;
  callType: number;
  timestampMs: number;
}

export interface CompanionSnapshot {
  connectionStatus: string;
  isServerRunning: boolean;
  isOverlayRunning: boolean;
  songTitle: string;
  artistName: string;
  isPlaying: boolean;
  progressMs: number;
  durationMs: number;
  volumePercent: number;
  batteryLevel: number;
  batteryTempC: number;
  currentSpeedKmh: number;
  isCallActive: boolean;
  isCallIncoming: boolean;
  isCallMuted: boolean;
  callStartedAtMs: number;
  callerName: string;
  callerNumber: string;
  sessionMissedCalls: number;
  notifications: MirrorNotification[];
  contacts: SyncedContact[];
  recentCalls: RecentCallEntry[];
  hasNotificationAccess: boolean;
  hasOverlayPermission: boolean;
  hasRuntimePermissions: boolean;
}

type PillionNativeModuleInterface = {
  getSnapshot(): Promise<CompanionSnapshot>;
  getPairedDevices(): Promise<PairedDevice[]>;
  requestRuntimePermissions(): Promise<boolean>;
  openNotificationListenerSettings(): Promise<void>;
  openOverlaySettings(): Promise<void>;
  startServer(): Promise<void>;
  stopServer(): Promise<void>;
  setRelayRole(mode: 'receiver' | 'sender'): Promise<void>;
  connectToDevice(address: string): Promise<void>;
  disconnect(): Promise<void>;
  setAutoReconnect(enabled: boolean): Promise<void>;
  startOverlay(): Promise<boolean>;
  stopOverlay(): Promise<void>;
  setAutoStartOverlay(enabled: boolean): Promise<void>;
  setKeepScreenAwake(enabled: boolean): Promise<void>;
  syncContacts(): Promise<void>;
  launchGoogleMaps(splitScreen: boolean): Promise<boolean>;
  sendCommand(path: string): Promise<void>;
  addListener?(
    eventName: 'onStateChange',
    listener: (snapshot: CompanionSnapshot) => void
  ): { remove: () => void };
};

const nativeModule =
  requireOptionalNativeModule<PillionNativeModuleInterface>('PillionNative');

export const PillionNative = nativeModule;

export const EMPTY_SNAPSHOT: CompanionSnapshot = {
  connectionStatus: 'Disconnected',
  isServerRunning: false,
  isOverlayRunning: false,
  songTitle: 'No Media Playing',
  artistName: 'Unknown Artist',
  isPlaying: false,
  progressMs: 0,
  durationMs: 0,
  volumePercent: 70,
  batteryLevel: -1,
  batteryTempC: -1,
  currentSpeedKmh: 0,
  isCallActive: false,
  isCallIncoming: false,
  isCallMuted: false,
  callStartedAtMs: 0,
  callerName: '',
  callerNumber: '',
  sessionMissedCalls: 0,
  notifications: [],
  contacts: [],
  recentCalls: [],
  hasNotificationAccess: false,
  hasOverlayPermission: false,
  hasRuntimePermissions: false,
};

