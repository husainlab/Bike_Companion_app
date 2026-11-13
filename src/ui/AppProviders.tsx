import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { ThemeProvider as NavThemeProvider } from 'expo-router';
import { useAppState } from '@/state/app';
import { buildNavigationTheme } from '@/ui/theme';
import { ThemeProvider } from '@/ui/ThemeContext';
import { ToastHost } from '@/ui/Toast';

export function AppProviders({ children }: { children: React.ReactNode }) {
  const { settings } = useAppState();
  const navTheme = buildNavigationTheme(settings.theme);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <NavThemeProvider value={navTheme}>
          {/* ThemeProvider makes the palette available via usePalette() — no per-leaf useAppState() calls */}
          <ThemeProvider>
            {children}
            <ToastHost />
          </ThemeProvider>
        </NavThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
