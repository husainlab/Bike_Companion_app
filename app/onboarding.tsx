import React, { useState } from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { RelayMode } from '@/core/types';
import { completeOnboarding } from '@/actions/cockpit';
import { requestAllPillionPermissions } from '@/lib/permissions';
import { getStore, useAppState } from '@/state/app';
import { initCompanionBridge } from '@/state/companion';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { PillionMark } from '@/ui/PillionMark';
import { Press } from '@/ui/Press';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

export default function OnboardingScreen() {
  const insets = useSafeAreaInsets();
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const [selected, setSelected] = useState<RelayMode>('receiver');
  const [busy, setBusy] = useState(false);

  const handleContinue = async () => {
    setBusy(true);
    try {
      await requestAllPillionPermissions(selected).catch(() => false);
      await completeOnboarding(getStore(), selected);
      await initCompanionBridge(selected).catch(() => {});
    } catch (err) {
      const detail = err instanceof Error && err.message ? `: ${err.message}` : '';
      showToast(`Failed to save device role${detail}`, 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <View
      style={{ paddingTop: insets.top + 24, paddingBottom: Math.max(insets.bottom, 20) }}
      className="flex-1 bg-background px-5 justify-between"
    >
      <View className="gap-6">
        <View className="flex-row items-center justify-between">
          <View className="flex-row items-center gap-3">
            <PillionMark size={36} color={palette.primary} />
            <Text className="font-mono-bold text-sm uppercase tracking-widest text-primary">
              PILLION
            </Text>
          </View>
          <Text className="font-mono text-xs text-foreground-muted">AMOLED COCKPIT</Text>
        </View>

        <View className="gap-2 pr-4">
          <Text className="font-bold text-3xl text-foreground">
            One phone in your pocket. One on the bars.
          </Text>
          <Text className="text-base text-foreground-muted">
            Pair both Android phones in system Bluetooth first, then assign what this device does.
          </Text>
        </View>

        <View className="gap-3">
          <Press
            onPress={() => setSelected('receiver')}
            className={`rounded-md border-[1.5px] p-4 ${
              selected === 'receiver'
                ? 'border-primary bg-surface'
                : 'border-border bg-background'
            }`}
          >
            <View className="flex-row items-start gap-3">
              <Icon
                name="smartphone"
                size={24}
                color={selected === 'receiver' ? palette.primary : palette.foregroundMuted}
              />
              <View className="flex-1 gap-1">
                <Text className="font-bold text-lg text-foreground">
                  Handlebar Display (Receiver)
                </Text>
                <Text className="text-sm text-foreground-muted">
                  Mounts on your bike or dash. Shows track controls, incoming calls, pocket battery, and a floating overlay bubble.
                </Text>
              </View>
            </View>
          </Press>

          <Press
            onPress={() => setSelected('sender')}
            className={`rounded-md border-[1.5px] p-4 ${
              selected === 'sender'
                ? 'border-primary bg-surface'
                : 'border-border bg-background'
            }`}
          >
            <View className="flex-row items-start gap-3">
              <Icon
                name="radio"
                size={24}
                color={selected === 'sender' ? palette.primary : palette.foregroundMuted}
              />
              <View className="flex-1 gap-1">
                <Text className="font-bold text-lg text-foreground">
                  Pocket Relay (Sender)
                </Text>
                <Text className="text-sm text-foreground-muted">
                  Stays zipped in your jacket. Streams media state, alerts, caller ID, and battery level over Bluetooth SPP.
                </Text>
              </View>
            </View>
          </Press>
        </View>
      </View>

      <Card tone="muted" className="gap-3">
        <Text className="text-xs font-mono uppercase tracking-wider text-foreground-muted">
          You can switch roles anytime from the top bar.
        </Text>
        <Button
          label={selected === 'receiver' ? 'Enter Cockpit' : 'Configure Pocket Relay'}
          icon="chevronRight"
          loading={busy}
          onPress={handleContinue}
        />
      </Card>
    </View>
  );
}

