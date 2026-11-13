import React from 'react';
import { Switch, View } from 'react-native';
import { useRouter } from 'expo-router';
import {
  setAutoStartOverlay,
  setAutoVolumeEnabled,
  setKeepAwakeOnRide,
  setThemePreference,
} from '@/actions/cockpit';
import {
  openNotificationAccessSettings,
  openOverlayPermissionSettings,
  setNativeAutoStartOverlay,
} from '@/data/companion';
import { getStore, useAppState } from '@/state/app';
import {
  setCompanionAutoStartOverlay,
  setCompanionAutoVolumeEnabled,
} from '@/state/companion';
import { checkForAppUpdate, getUpdate, setUpdateCheck, useUpdate } from '@/state/update';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { getPalette } from '@/ui/palette';
import { Segmented } from '@/ui/Segmented';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { UpdateCard } from '@/ui/shells/UpdateCard';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

export default function SettingsScreen() {
  const router = useRouter();
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const update = useUpdate();

  const handleManualUpdateCheck = async () => {
    const found = await checkForAppUpdate(true);
    const latestState = getUpdate();
    if (found) {
      showToast(`Pillion v${found.version} is available`, 'success');
    } else if (latestState.checkError) {
      showToast(latestState.checkError, 'error');
    } else {
      showToast(`You are on the latest release (v${latestState.currentVersion})`, 'info');
    }
  };

  return (
    <PageShell title="Setup" eyebrow="DISPLAY & UPDATES">
      <ScreenScroll>
        <Card className="gap-3">
          <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
            DISPLAY & BATTERY
          </Text>
          <Text className="text-sm text-foreground-muted">
            AMOLED Black turns off background pixels on OLED screens to preserve phone battery on long rides.
          </Text>
          <Segmented
            options={[
              { value: 'dark', label: 'AMOLED Black' },
              { value: 'light', label: 'Daylight Chalk' },
            ]}
            value={settings.theme}
            onChange={(next) => setThemePreference(getStore(), next)}
          />

          <View className="flex-row items-center justify-between pt-2">
            <View className="flex-1 pr-3 min-w-0">
              <Text className="font-bold text-base text-foreground leading-snug" numberOfLines={2}>
                Keep screen awake
              </Text>
              <Text className="text-xs text-foreground-muted leading-normal" numberOfLines={2}>
                Prevents screen timeout while in Handlebar mode
              </Text>
            </View>
            <Switch
              value={settings.keepAwakeOnRide}
              onValueChange={(v) => {
                void setKeepAwakeOnRide(getStore(), v);
              }}
              trackColor={{ false: palette.border, true: palette.primary }}
              thumbColor={palette.foreground}
            />
          </View>

          <View className="flex-row items-center justify-between pt-1">
            <View className="flex-1 pr-3 min-w-0">
              <Text className="font-bold text-base text-foreground leading-snug" numberOfLines={2}>
                Auto-start overlay on connect
              </Text>
              <Text className="text-xs text-foreground-muted leading-normal" numberOfLines={2}>
                Requires Display Over Apps permission
              </Text>
            </View>
            <Switch
              value={settings.autoStartOverlay}
              onValueChange={(v) => {
                setCompanionAutoStartOverlay(v);
                setNativeAutoStartOverlay(v).catch(() => {});
                void setAutoStartOverlay(getStore(), v);
              }}
              trackColor={{ false: palette.border, true: palette.primary }}
              thumbColor={palette.foreground}
            />
          </View>

          <View className="flex-row items-center justify-between pt-3 border-t border-border">
            <View className="flex-1 pr-3 min-w-0">
              <Text className="font-bold text-base text-foreground leading-snug" numberOfLines={2}>
                Speed-sensitive auto volume
              </Text>
              <Text className="text-xs text-foreground-muted leading-normal" numberOfLines={2}>
                Compensates for helmet wind noise at highway speeds
              </Text>
            </View>
            <Switch
              value={settings.autoVolumeEnabled}
              onValueChange={(v) => {
                setCompanionAutoVolumeEnabled(v);
                void setAutoVolumeEnabled(getStore(), v);
              }}
              trackColor={{ false: palette.border, true: palette.primary }}
              thumbColor={palette.foreground}
            />
          </View>
        </Card>

        <Card className="gap-3">
          <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
            SYSTEM PERMISSIONS
          </Text>
          <Button
            label="Notification Listener Settings"
            icon="bell"
            tone="surface"
            compact
            onPress={() => openNotificationAccessSettings()}
          />
          <Button
            label="Display Over Apps Settings"
            icon="layers"
            tone="surface"
            compact
            onPress={() => openOverlayPermissionSettings()}
          />
        </Card>

        <UpdateCard />

        <Card className="gap-3">
          <View className="flex-row items-center justify-between">
            <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
              APP & RELEASES
            </Text>
            <Text className="font-mono-bold text-xs text-primary">
              v{update.currentVersion}
            </Text>
          </View>

          <View className="flex-row items-center justify-between">
            <View className="flex-1 pr-3">
              <Text className="font-bold text-base text-foreground">
                Check GitHub Releases automatically
              </Text>
              <Text className="text-xs text-foreground-muted">
                Checks at most once daily on launch
              </Text>
            </View>
            <Switch
              value={update.enabled}
              onValueChange={(v) => setUpdateCheck(v)}
              trackColor={{ false: palette.border, true: palette.primary }}
              thumbColor={palette.foreground}
            />
          </View>

          <Button
            label={update.checking ? 'Checking GitHub...' : 'Check for updates'}
            icon="refresh"
            tone="primary"
            loading={update.checking}
            onPress={handleManualUpdateCheck}
          />
        </Card>

        {__DEV__ ? (
          <Button
            label="Open Dev Diagnostics"
            icon="settings"
            tone="ghost"
            compact
            onPress={() => router.push('/dev')}
          />
        ) : null}
      </ScreenScroll>
    </PageShell>
  );
}
