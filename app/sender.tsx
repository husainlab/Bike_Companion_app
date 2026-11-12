import React, { useEffect } from 'react';
import { Redirect } from 'expo-router';
import { setRelayMode } from '@/actions/cockpit';
import { getStore } from '@/state/app';
import { initCompanionBridge } from '@/state/companion';

export default function SenderShortcutRoute() {
  useEffect(() => {
    setRelayMode(getStore(), 'sender')
      .then(() => initCompanionBridge(false))
      .catch(() => {});
  }, []);

  return <Redirect href="/(tabs)" />;
}

