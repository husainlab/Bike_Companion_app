export type LinkBadgeTone = 'active' | 'linking' | 'offline' | 'alert';

export interface LinkBadge {
  label: string;
  tone: LinkBadgeTone;
}

/**
 * Pure status mapping for the handlebar cockpit header.
 */
export function resolveLinkBadge(
  connectionStatus: string,
  isServerRunning: boolean,
  isCallActive: boolean
): LinkBadge {
  if (isCallActive) {
    return { label: 'CALL', tone: 'alert' };
  }
  if (connectionStatus === 'Connected') {
    return { label: 'LINKED', tone: 'active' };
  }
  if (
    connectionStatus === 'Connecting...' ||
    connectionStatus === 'Auto-reconnecting...' ||
    connectionStatus === 'Waiting for connection...' ||
    isServerRunning
  ) {
    return { label: 'WAIT', tone: 'linking' };
  }
  if (connectionStatus === 'Permission denied' || connectionStatus.includes('Error') || connectionStatus.includes('failed')) {
    return { label: 'FAULT', tone: 'alert' };
  }
  return { label: 'IDLE', tone: 'offline' };
}

export function clampProgress(positionMs: number, durationMs: number): number {
  if (!Number.isFinite(positionMs) || !Number.isFinite(durationMs) || durationMs <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(1, positionMs / durationMs));
}

export function isBatteryLow(batteryLevel: number): boolean {
  return batteryLevel >= 0 && batteryLevel <= 20;
}

export function formatBatteryLabel(batteryLevel: number): string {
  if (batteryLevel < 0 || !Number.isFinite(batteryLevel)) return '--%';
  return `${Math.round(batteryLevel)}%`;
}

export interface ContactItem {
  id: string;
  name: string;
  phoneNumber: string;
  isStarred: boolean;
}

export const RIDING_QUICK_REPLIES = [
  'Riding now, call you later',
  'On my way',
  'OK',
] as const;

/**
 * Filter synced contacts by name or numeric phone substring, preserving starred entries first.
 */
export function filterContacts<T extends ContactItem>(
  contacts: readonly T[],
  query: string
): T[] {
  const trimmed = query.trim().toLowerCase();
  const digitsQuery = trimmed.replace(/\D+/g, '');

  const filtered = trimmed
    ? contacts.filter((contact) => {
        if (contact.name.toLowerCase().includes(trimmed)) {
          return true;
        }
        if (digitsQuery.length > 0) {
          const phoneDigits = contact.phoneNumber.replace(/\D+/g, '');
          return phoneDigits.includes(digitsQuery);
        }
        return contact.phoneNumber.toLowerCase().includes(trimmed);
      })
    : [...contacts];

  return filtered.sort((a, b) => {
    if (a.isStarred !== b.isStarred) {
      return a.isStarred ? -1 : 1;
    }
    return a.name.localeCompare(b.name);
  });
}

/**
 * Human-readable label for Android CallLog.Calls.TYPE integers.
 */
export function formatCallTypeLabel(callType: number): 'INCOMING' | 'OUTGOING' | 'MISSED' | 'CALL' {
  if (callType === 1) return 'INCOMING';
  if (callType === 2) return 'OUTGOING';
  if (callType === 3) return 'MISSED';
  return 'CALL';
}

