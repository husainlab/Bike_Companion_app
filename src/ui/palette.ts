export interface ThemePalette {
  background: string;
  surface: string;
  surfaceMuted: string;
  foreground: string;
  foregroundMuted: string;
  primary: string;
  onPrimary: string;
  accent: string;
  onAccent: string;
  border: string;
  danger: string;
  warning: string;
  scrim: string;
  edge: string;
}

export const LIGHT_PALETTE: ThemePalette = {
  background: '#F5F3EE',
  surface: '#FFFFFF',
  surfaceMuted: '#E8E4DA',
  foreground: '#171916',
  foregroundMuted: '#595D55',
  primary: '#D94E1F',
  onPrimary: '#FFFBF7',
  accent: '#1E5B45',
  onAccent: '#FFFFFF',
  border: '#D1CCC0',
  danger: '#B3261E',
  warning: '#B45309',
  scrim: 'rgb(23 25 22 / 0.55)',
  edge: 'rgb(23 25 22 / 0.14)',
};

export const DARK_PALETTE: ThemePalette = {
  background: '#000000',
  surface: '#0A0B0A',
  surfaceMuted: '#141614',
  foreground: '#FFFFFF',
  foregroundMuted: '#B5BAB3',
  primary: '#FF5A1F',
  onPrimary: '#000000',
  accent: '#00E096',
  onAccent: '#000000',
  border: '#242824',
  danger: '#FF453A',
  warning: '#FFB340',
  scrim: 'rgb(0 0 0 / 0.78)',
  edge: 'rgb(255 255 255 / 0.14)',
};

export function getPalette(scheme: 'dark' | 'light'): ThemePalette {
  return scheme === 'light' ? LIGHT_PALETTE : DARK_PALETTE;
}

