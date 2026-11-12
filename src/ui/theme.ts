import { DarkTheme, DefaultTheme, type Theme } from 'expo-router';
import { getPalette } from './palette';

export function buildNavigationTheme(scheme: 'dark' | 'light'): Theme {
  const c = getPalette(scheme);
  const base = scheme === 'light' ? DefaultTheme : DarkTheme;
  return {
    ...base,
    dark: scheme === 'dark',
    colors: {
      ...base.colors,
      primary: c.primary,
      background: c.background,
      card: c.surface,
      text: c.foreground,
      border: c.border,
      notification: c.danger,
    },
  };
}
