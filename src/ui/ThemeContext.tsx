/**
 * ThemeContext — provides the current ThemePalette to the entire component tree.
 *
 * Replaces the pattern of calling `useAppState()` + `getPalette()` in every leaf
 * component (Button, Icon, etc.). Previously each Button instance subscribed to the
 * full app-state atom independently; with the cockpit screen rendering 15+ buttons
 * this meant 15+ separate subscriptions re-rendering on any settings change.
 *
 * Usage:
 *   const palette = usePalette();
 */
import React, { createContext, useContext } from 'react';
import { getPalette } from './palette';
import type { ThemePalette } from './palette';
import { useAppState } from '@/state/app';

const ThemeContext = createContext<ThemePalette>(getPalette('dark'));

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  return <ThemeContext.Provider value={palette}>{children}</ThemeContext.Provider>;
}

export function usePalette(): ThemePalette {
  return useContext(ThemeContext);
}

