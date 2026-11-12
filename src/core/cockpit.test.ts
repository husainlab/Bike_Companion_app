import { describe, expect, it } from 'vitest';
import {
  clampProgress,
  filterContacts,
  formatBatteryLabel,
  formatCallTypeLabel,
  isBatteryLow,
  resolveLinkBadge,
  RIDING_QUICK_REPLIES,
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

  it('filters contacts by name or digits and keeps starred entries first', () => {
    const sample = [
      { id: '1', name: 'Zed', phoneNumber: '+1 555-0102', isStarred: false },
      { id: '2', name: 'Alice', phoneNumber: '+1 555-0199', isStarred: false },
      { id: '3', name: 'Mom', phoneNumber: '+1 555-0100', isStarred: true },
    ];
    expect(filterContacts(sample, '').map((c) => c.id)).toEqual(['3', '2', '1']);
    expect(filterContacts(sample, '5550199').map((c) => c.id)).toEqual(['2']);
    expect(filterContacts(sample, 'mom').map((c) => c.id)).toEqual(['3']);
  });

  it('formats call log types and exposes riding quick replies', () => {
    expect(formatCallTypeLabel(1)).toBe('INCOMING');
    expect(formatCallTypeLabel(2)).toBe('OUTGOING');
    expect(formatCallTypeLabel(3)).toBe('MISSED');
    expect(formatCallTypeLabel(99)).toBe('CALL');
    expect(RIDING_QUICK_REPLIES.length).toBeGreaterThanOrEqual(2);
  });
});

