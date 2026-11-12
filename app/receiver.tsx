import React, { useEffect } from 'react';
import { Redirect } from 'expo-router';
import { setRelayMode } from '@/actions/cockpit';
import { getStore } from '@/state/app';
import { initCompanionBridge, teardownPreviousRelayMode } from '@/state/companion';

export default function ReceiverShortcutRoute() {
  useEffect(() => {
    setRelayMode(getStore(), 'receiver')
      .then(() => teardownPreviousRelayMode('sender'))
      .then(() => initCompanionBridge('receiver'))
      .catch(() => {});
  }, []);

  return <Redirect href="/(tabs)" />;
}

