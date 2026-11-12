import { describe, expect, it } from 'vitest';
import { keepEqual } from './keepEqual';

describe('keepEqual structural sharing', () => {
  it('returns identical reference when deeply equal', () => {
    const prev = { a: 1, items: [{ id: '1', name: 'BT' }] };
    const next = { a: 1, items: [{ id: '1', name: 'BT' }] };
    expect(keepEqual(prev, next)).toBe(prev);
  });

  it('keeps unchanged branches while updating changed leaves', () => {
    const unchanged = { id: 'a', ok: true };
    const prev = { unchanged, count: 1 };
    const next = { unchanged: { id: 'a', ok: true }, count: 2 };
    const result = keepEqual(prev, next);
    expect(result).not.toBe(prev);
    expect(result.unchanged).toBe(unchanged);
    expect(result.count).toBe(2);
  });
});

