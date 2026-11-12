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

