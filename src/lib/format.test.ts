import { describe, expect, it } from 'vitest';
import { formatPercent, shortAddress } from './format';

describe('format helpers', () => {
  it('clamps ratio and formats percentage strings', () => {
    expect(formatPercent(0.5)).toBe('50%');
    expect(formatPercent(-0.2)).toBe('0%');
    expect(formatPercent(1.8)).toBe('100%');
    expect(formatPercent(Number.NaN)).toBe('0%');
  });

  it('truncates long Bluetooth MAC addresses cleanly', () => {
    expect(shortAddress('AA:BB:CC:DD:EE:FF')).toBe('AA:BB:CC...EE:FF');
    expect(shortAddress('Short')).toBe('Short');
  });
});

