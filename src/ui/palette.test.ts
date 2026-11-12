import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { DARK_PALETTE, LIGHT_PALETTE, type ThemePalette } from './palette';

function hexToTriplet(hex: string): string {
  const clean = hex.replace('#', '');
  const r = Number.parseInt(clean.slice(0, 2), 16);
  const g = Number.parseInt(clean.slice(2, 4), 16);
  const b = Number.parseInt(clean.slice(4, 6), 16);
  return `${r} ${g} ${b}`;
}

function parseCssBlock(css: string, selectorRegex: RegExp): Record<string, string> {
  const match = css.match(selectorRegex);
  if (!match) return {};
  const map: Record<string, string> = {};
  const varRegex = /--color-([a-z0-9-]+)\s*:\s*([^;]+);/g;
  let m: RegExpExecArray | null;
  while ((m = varRegex.exec(match[1])) !== null) {
    map[m[1]] = m[2].trim();
  }
  return map;
}

const KEY_TO_CSS: Record<keyof ThemePalette, string> = {
  background: 'background',
  surface: 'surface',
  surfaceMuted: 'surface-muted',
  foreground: 'foreground',
  foregroundMuted: 'foreground-muted',
  primary: 'primary',
  onPrimary: 'on-primary',
  accent: 'accent',
  onAccent: 'on-accent',
  border: 'border',
  danger: 'danger',
  warning: 'warning',
  scrim: 'scrim',
  edge: 'edge',
};

describe('palette.ts parity with global.css', () => {
  const css = fs.readFileSync(path.resolve(process.cwd(), 'global.css'), 'utf8');
  const lightVars = parseCssBlock(css, /(?:^|\n):root\s*\{([\s\S]*?)\}/);
  const darkVars = parseCssBlock(css, /\.dark:root\s*\{([\s\S]*?)\}/);

  it('matches light palette values 1:1 with global.css :root', () => {
    for (const [key, cssName] of Object.entries(KEY_TO_CSS) as [keyof ThemePalette, string][]) {
      const value = LIGHT_PALETTE[key];
      const expected = value.startsWith('#') ? hexToTriplet(value) : value;
      expect(lightVars[cssName], `Mismatch for light.${key}`).toBe(expected);
    }
  });

  it('matches AMOLED dark palette values 1:1 with global.css .dark:root', () => {
    for (const [key, cssName] of Object.entries(KEY_TO_CSS) as [keyof ThemePalette, string][]) {
      const value = DARK_PALETTE[key];
      const expected = value.startsWith('#') ? hexToTriplet(value) : value;
      expect(darkVars[cssName], `Mismatch for dark.${key}`).toBe(expected);
    }
  });
});

