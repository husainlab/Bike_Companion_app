import React, { useEffect } from 'react';
import { Redirect } from 'expo-router';
import { setRelayMode } from '@/actions/cockpit';
import { getStore } from '@/state/app';
import { initCompanionBridge, teardownPreviousRelayMode } from '@/state/companion';

export default function SenderShortcutRoute() {
  useEffect(() => {
    setRelayMode(getStore(), 'sender')
      .then(() => teardownPreviousRelayMode('receiver'))
      .then(() => initCompanionBridge('sender'))
      .catch(() => {});
  }, []);

  return <Redirect href="/(tabs)" />;
}

