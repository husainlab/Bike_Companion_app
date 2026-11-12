import { describe, expect, it } from 'vitest';
import { formatCallElapsed, formatDurationMs, formatRelativeTime } from './time';

describe('time helpers', () => {
  it('formats playback durations safely', () => {
    expect(formatDurationMs(0)).toBe('00:00');
    expect(formatDurationMs(-500)).toBe('00:00');
    expect(formatDurationMs(Number.NaN)).toBe('00:00');
    expect(formatDurationMs(65_000)).toBe('01:05');
  });

  it('formats call elapsed time across invalid ranges, mm:ss, and h:mm:ss', () => {
    expect(formatCallElapsed(0, 10_000)).toBe('00:00');
    expect(formatCallElapsed(10_000, 5000)).toBe('00:00');
    expect(formatCallElapsed(Number.NaN, 5000)).toBe('00:00');
    expect(formatCallElapsed(1000, 46_000)).toBe('00:45');
    expect(formatCallElapsed(1000, 126_000)).toBe('02:05');
    expect(formatCallElapsed(1000, 3_726_000)).toBe('1:02:05');
  });

  it('formats relative timestamps across seconds, minutes, hours, and days', () => {
    const now = 1_700_000_000_000;
    expect(formatRelativeTime(now - 20_000, now)).toBe('just now');
    expect(formatRelativeTime(now - 5 * 60_000, now)).toBe('5m ago');
    expect(formatRelativeTime(now - 3 * 3_600_000, now)).toBe('3h ago');
    expect(formatRelativeTime(now - 2 * 86_400_000, now)).toBe('2d ago');
  });
});

