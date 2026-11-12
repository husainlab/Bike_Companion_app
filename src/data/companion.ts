import {
  EMPTY_SNAPSHOT,
  PillionNative,
  type CompanionSnapshot,
  type PairedDevice,
} from '../../modules/pillion-native';

export type { CompanionSnapshot, PairedDevice };

export async function getCompanionSnapshot(): Promise<CompanionSnapshot> {
  if (!PillionNative) return EMPTY_SNAPSHOT;
  return PillionNative.getSnapshot();
}

export async function getPairedBluetoothDevices(): Promise<PairedDevice[]> {
  if (!PillionNative) return [];
  return PillionNative.getPairedDevices();
}

export async function startSenderServer(): Promise<void> {
  await PillionNative?.startServer();
}

export async function stopSenderServer(): Promise<void> {
  await PillionNative?.stopServer();
}

export async function connectToSenderDevice(address: string): Promise<void> {
  await PillionNative?.connectToDevice(address);
}

export async function disconnectCompanion(): Promise<void> {
  await PillionNative?.disconnect();
}

export async function setAutoReconnect(enabled: boolean): Promise<void> {
  await PillionNative?.setAutoReconnect(enabled);
}

export async function startCockpitOverlay(): Promise<boolean> {
  if (!PillionNative) return false;
  return PillionNative.startOverlay();
}

export async function stopCockpitOverlay(): Promise<void> {
  await PillionNative?.stopOverlay();
}

export async function openNotificationAccessSettings(): Promise<void> {
  await PillionNative?.openNotificationListenerSettings();
}

export async function openOverlayPermissionSettings(): Promise<void> {
  await PillionNative?.openOverlaySettings();
}

export type {
  MirrorNotification,
  RecentCallEntry,
  SyncedContact,
} from '../../modules/pillion-native';

export async function sendCockpitCommand(
  path:
    | '/media/playpause'
    | '/media/next'
    | '/media/prev'
    | '/volume/up'
    | '/volume/down'
    | '/call/answer'
    | '/call/decline'
    | '/sync/contacts'
    | `/call/dial|${string}`
    | `/notif/dismiss|${string}`
    | `/notif/reply|${string}|${string}`
): Promise<void> {
  await PillionNative?.sendCommand(path);
}

export async function dialContactNumber(phoneNumber: string): Promise<void> {
  const cleaned = phoneNumber.trim();
  if (!cleaned) return;
  await sendCockpitCommand(`/call/dial|${cleaned}`);
}

export async function syncCompanionContacts(): Promise<void> {
  await PillionNative?.syncContacts();
  await sendCockpitCommand('/sync/contacts');
}

export async function dismissMirroredNotification(key: string): Promise<void> {
  if (!key) return;
  await sendCockpitCommand(`/notif/dismiss|${key}`);
}

export async function replyToMirroredNotification(
  key: string,
  text: string
): Promise<void> {
  const cleaned = text.trim();
  if (!key || !cleaned) return;
  await sendCockpitCommand(`/notif/reply|${key}|${cleaned}`);
}

export async function launchLocalGoogleMaps(splitScreen: boolean): Promise<boolean> {
  if (!PillionNative) return false;
  return await PillionNative.launchGoogleMaps(splitScreen);
}

export function subscribeCompanionState(
  listener: (snapshot: CompanionSnapshot) => void
): () => void {
  if (!PillionNative?.addListener) {
    return () => {};
  }
  const sub = PillionNative.addListener('onStateChange', listener);
  return () => sub.remove();
}

