import { describe, expect, it } from 'vitest';
import {
  clampProgress,
  formatBatteryLabel,
  isBatteryLow,
  resolveLinkBadge,
} from './cockpit';

describe('cockpit pure domain helpers', () => {
  it('resolves link badges cleanly by priority', () => {
    expect(resolveLinkBadge('Connected', false, true)).toEqual({
      label: 'CALL',
      tone: 'alert',
    });
    expect(resolveLinkBadge('Connected', false, false)).toEqual({
      label: 'LINKED',
      tone: 'active',
    });
    expect(resolveLinkBadge('Auto-reconnecting...', false, false)).toEqual({
      label: 'WAIT',
      tone: 'linking',
    });
    expect(resolveLinkBadge('Disconnected', true, false)).toEqual({
      label: 'WAIT',
      tone: 'linking',
    });
    expect(resolveLinkBadge('Permission denied', false, false)).toEqual({
      label: 'FAULT',
      tone: 'alert',
    });
    expect(resolveLinkBadge('Disconnected', false, false)).toEqual({
      label: 'IDLE',
      tone: 'offline',
    });
  });

  it('clamps playback progress safely', () => {
    expect(clampProgress(500, 1000)).toBe(0.5);
    expect(clampProgress(-10, 1000)).toBe(0);
    expect(clampProgress(2000, 1000)).toBe(1);
    expect(clampProgress(500, 0)).toBe(0);
  });

  it('detects low battery and formats battery telemetry', () => {
    expect(isBatteryLow(-1)).toBe(false);
    expect(isBatteryLow(18)).toBe(true);
    expect(isBatteryLow(21)).toBe(false);
    expect(formatBatteryLabel(-1)).toBe('--%');
    expect(formatBatteryLabel(87.4)).toBe('87%');
  });
});

