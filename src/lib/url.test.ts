import { describe, expect, it } from 'vitest';
import { isSafeUrl } from './url';

describe('url allowlist', () => {
  it('allows https, http, and pillion deep links', () => {
    expect(isSafeUrl('https://github.com/husainlab/Bike_Companion_app')).toBe(true);
    expect(isSafeUrl('http://localhost:8081')).toBe(true);
    expect(isSafeUrl('pillion://receiver')).toBe(true);
  });

  it('rejects unsafe schemes and invalid URLs', () => {
    expect(isSafeUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeUrl('file:///etc/passwd')).toBe(false);
    expect(isSafeUrl('not a valid url')).toBe(false);
  });
});

