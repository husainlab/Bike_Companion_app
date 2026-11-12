import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { bumpPatch, bumpProjectVersions } from './bump-version.mjs';

describe('version bump script', () => {
  it('increments patch semver strictly', () => {
    expect(bumpPatch('1.0.0')).toBe('1.0.1');
    expect(bumpPatch('2.4.9')).toBe('2.4.10');
    expect(() => bumpPatch('1.0')).toThrow();
  });

  it('bumps both app.json and package.json monotonically', () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'pillion-bump-'));
    fs.writeFileSync(
      path.join(tmp, 'app.json'),
      JSON.stringify({ expo: { version: '1.0.4', android: { versionCode: 5 } } })
    );
    fs.writeFileSync(
      path.join(tmp, 'package.json'),
      JSON.stringify({ name: 'pillion', version: '1.0.4' })
    );

    const res = bumpProjectVersions(tmp);
    expect(res).toEqual({ newVersion: '1.0.5', versionCode: 6 });

    const appAfter = JSON.parse(fs.readFileSync(path.join(tmp, 'app.json'), 'utf8'));
    const pkgAfter = JSON.parse(fs.readFileSync(path.join(tmp, 'package.json'), 'utf8'));
    expect(appAfter.expo.version).toBe('1.0.5');
    expect(appAfter.expo.android.versionCode).toBe(6);
    expect(pkgAfter.version).toBe('1.0.5');
  });
});

