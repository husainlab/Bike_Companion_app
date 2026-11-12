import { describe, expect, it } from 'vitest';
import { ABI_ASSET, apkFor, isNewer, parseRelease, parseSemver } from './release';

describe('release parser and semver helpers', () => {
  it('parses strict 3-part semver tags and rejects prereleases/invalid tags', () => {
    expect(parseSemver('v1.2.3')).toEqual([1, 2, 3]);
    expect(parseSemver('1.2.3')).toEqual([1, 2, 3]);
    expect(parseSemver('v1.2.3-beta.1')).toBeNull();
    expect(parseSemver('v1.2')).toBeNull();
  });

  it('compares semver correctly', () => {
    expect(isNewer('1.0.1', '1.0.0')).toBe(true);
    expect(isNewer('1.1.0', '1.0.9')).toBe(true);
    expect(isNewer('2.0.0', '1.9.9')).toBe(true);
    expect(isNewer('1.0.0', '1.0.0')).toBe(false);
    expect(isNewer('1.0.0', '1.0.1')).toBe(false);
    expect(isNewer('invalid', '1.0.0')).toBe(false);
  });

  it('rejects drafts and prereleases', () => {
    const base = {
      tag_name: 'v1.2.0',
      html_url: 'https://github.com/husainlab/Bike_Companion_app/releases/tag/v1.2.0',
      assets: [
        {
          name: 'Pillion-1.2.0.apk',
          browser_download_url: 'https://github.com/r/Pillion-1.2.0.apk',
          url: 'https://api.github.com/repos/r/assets/1',
        },
      ],
    };
    expect(parseRelease({ ...base, draft: true })).toBeNull();
    expect(parseRelease({ ...base, prerelease: true })).toBeNull();
  });

  it('extracts versioned, release, and ABI-specific APK assets', () => {
    const parsed = parseRelease({
      tag_name: 'v1.2.0',
      html_url: 'https://github.com/husainlab/Bike_Companion_app/releases/tag/v1.2.0',
      body: 'Fixes Bluetooth auto-reconnect.',
      published_at: '2026-11-12T10:00:00Z',
      assets: [
        {
          name: 'Pillion-release.apk',
          browser_download_url: 'https://example.com/Pillion-release.apk',
          url: 'https://api.example.com/10',
        },
        {
          name: 'Pillion-1.2.0.apk',
          browser_download_url: 'https://example.com/Pillion-1.2.0.apk',
          url: 'https://api.example.com/11',
        },
        {
          name: 'Pillion-1.2.0-arm64.apk',
          browser_download_url: 'https://example.com/Pillion-1.2.0-arm64.apk',
          url: 'https://api.example.com/12',
        },
      ],
    });

    expect(parsed).not.toBeNull();
    expect(parsed?.version).toBe('1.2.0');
    expect(parsed?.apks.default).toBe('https://example.com/Pillion-1.2.0.apk');
    expect(parsed?.apks.byAbi.arm64).toBe('https://example.com/Pillion-1.2.0-arm64.apk');
    expect(parsed?.apiApks.byAbi.arm64).toBe('https://api.example.com/12');
    expect(ABI_ASSET['arm64-v8a']).toBe('arm64');
  });

  it('selects matching ABI first and falls back to default APK', () => {
    const parsed = parseRelease({
      tag_name: 'v1.3.0',
      html_url: 'https://example.com/rel',
      assets: [
        {
          name: 'Pillion-1.3.0.apk',
          browser_download_url: 'https://example.com/Pillion-1.3.0.apk',
          url: 'https://api.example.com/1',
        },
        {
          name: 'Pillion-1.3.0-armv7.apk',
          browser_download_url: 'https://example.com/Pillion-1.3.0-armv7.apk',
          url: 'https://api.example.com/2',
        },
      ],
    });
    expect(parsed).not.toBeNull();
    if (!parsed) return;

    expect(apkFor(parsed, ['armeabi-v7a', 'arm64-v8a'])).toBe(
      'https://example.com/Pillion-1.3.0-armv7.apk'
    );
    expect(apkFor(parsed, ['arm64-v8a'])).toBe(
      'https://example.com/Pillion-1.3.0.apk'
    );
  });
});

