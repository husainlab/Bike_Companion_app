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
  batteryLevel: number;
  isCallActive: boolean;
  isCallIncoming: boolean;
  callerName: string;
  notifications: MirrorNotification[];
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
  connectToDevice(address: string): Promise<void>;
  disconnect(): Promise<void>;
  setAutoReconnect(enabled: boolean): Promise<void>;
  startOverlay(): Promise<boolean>;
  stopOverlay(): Promise<void>;
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
  batteryLevel: -1,
  isCallActive: false,
  isCallIncoming: false,
  callerName: '',
  notifications: [],
  hasNotificationAccess: false,
  hasOverlayPermission: false,
  hasRuntimePermissions: false,
};

