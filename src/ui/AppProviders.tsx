import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { ThemeProvider } from 'expo-router';
import { useAppState } from '@/state/app';
import { buildNavigationTheme } from '@/ui/theme';
import { ToastHost } from '@/ui/Toast';

export function AppProviders({ children }: { children: React.ReactNode }) {
  const { settings } = useAppState();
  const navTheme = buildNavigationTheme(settings.theme);

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <ThemeProvider value={navTheme}>
          {children}
          <ToastHost />
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
