import '../global.css';
import React, { useEffect, useState } from 'react';
import {
  AtkinsonHyperlegible_400Regular,
  AtkinsonHyperlegible_400Regular_Italic,
  AtkinsonHyperlegible_700Bold,
  useFonts,
} from '@expo-google-fonts/atkinson-hyperlegible';
import {
  JetBrainsMono_500Medium,
  JetBrainsMono_700Bold,
} from '@expo-google-fonts/jetbrains-mono';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import * as SystemUI from 'expo-system-ui';
import { enableFreeze } from 'react-native-screens';
import {
  endActiveRideSession,
  incrementActiveRideNotifications,
} from '@/actions/cockpit';
import {
  setCompanionKeepScreenAwake,
  setNativeAutoStartOverlay,
  startCockpitOverlay,
} from '@/data/companion';
import { ScreenError } from '@/screens/ScreenError';
import { bootApp, getStore, useAppState } from '@/state/app';
import {
  initCompanionBridge,
  registerCompanionRideHandlers,
} from '@/state/companion';
import { maybeCheckForAppUpdate } from '@/state/update';
import { AppProviders } from '@/ui/AppProviders';
import { getPalette } from '@/ui/palette';

enableFreeze(false);

SplashScreen.preventAutoHideAsync().catch(() => {});
SplashScreen.setOptions({ duration: 0, fade: false });

export const unstable_settings = {
  anchor: '(tabs)',
  screenErrorBoundary: ScreenError,
};

export default function RootLayout() {
  const [fontsLoaded] = useFonts({
    AtkinsonHyperlegible_400Regular,
    AtkinsonHyperlegible_700Bold,
    AtkinsonHyperlegible_400Regular_Italic,
    JetBrainsMono_500Medium,
    JetBrainsMono_700Bold,
  });

  const [fontTimeoutElapsed, setFontTimeoutElapsed] = useState(false);
  const [booted, setBooted] = useState(false);
  const { settings } = useAppState();

  useEffect(() => {
    const fontTimer = setTimeout(() => {
      setFontTimeoutElapsed(true);
    }, 1200);
    const bootWatchdog = setTimeout(() => {
      setBooted(true);
    }, 2000);
    return () => {
      clearTimeout(fontTimer);
      clearTimeout(bootWatchdog);
    };
  }, []);

  const fontsReady = fontsLoaded || fontTimeoutElapsed;

  useEffect(() => {
    let active = true;
    bootApp()
      .then((appState) => {
        if (!active) return;
        setBooted(true);
        registerCompanionRideHandlers({
          onNotificationsAdded: (delta) => {
            incrementActiveRideNotifications(getStore(), delta).catch(() => {});
          },
          onDisconnected: () => {
            endActiveRideSession(getStore()).catch(() => {});
          },
          onAutoStartOverlay: () => {
            startCockpitOverlay().catch(() => {});
          },
        });
        setNativeAutoStartOverlay(appState.settings.autoStartOverlay).catch(() => {});
        initCompanionBridge(
          appState.settings.relayMode,
          appState.settings.autoStartOverlay,
          appState.settings.autoVolumeEnabled
        ).catch(() => {});
        maybeCheckForAppUpdate().catch(() => {});
      })
      .catch(() => {
        if (active) setBooted(true);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    const palette = getPalette(settings.theme);
    SystemUI.setBackgroundColorAsync(palette.background).catch(() => {});
  }, [settings.theme]);

  useEffect(() => {
    const shouldKeepAwake = settings.keepAwakeOnRide && settings.relayMode === 'receiver';
    if (shouldKeepAwake) {
      activateKeepAwakeAsync('pillion-ride').catch(() => {});
    } else {
      deactivateKeepAwake('pillion-ride').catch(() => {});
    }
    setCompanionKeepScreenAwake(shouldKeepAwake).catch(() => {});
  }, [settings.keepAwakeOnRide, settings.relayMode]);

  useEffect(() => {
    if (fontsReady && booted) {
      SplashScreen.hideAsync().catch(() => {});
    }
  }, [fontsReady, booted]);

  if (!fontsReady || !booted) {
    return null;
  }

  return (
    <AppProviders>
      <StatusBar style={settings.theme === 'dark' ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerShown: false, animation: 'fade_from_bottom' }}>
        <Stack.Protected guard={!settings.onboardingComplete}>
          <Stack.Screen name="onboarding" />
        </Stack.Protected>
        <Stack.Protected guard={settings.onboardingComplete}>
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="connect" />
          <Stack.Screen name="receiver" />
          <Stack.Screen name="sender" />
          <Stack.Screen name="dev/index" />
        </Stack.Protected>
      </Stack>
    </AppProviders>
  );
}

