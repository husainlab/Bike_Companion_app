import React from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { setRelayMode } from '@/actions/cockpit';
import {
  clampProgress,
  formatBatteryLabel,
  isBatteryLow,
  resolveLinkBadge,
} from '@/core/cockpit';
import type { RelayMode } from '@/core/types';
import {
  disconnectCompanion,
  openNotificationAccessSettings,
  openOverlayPermissionSettings,
  sendCockpitCommand,
  startCockpitOverlay,
  startSenderServer,
  stopCockpitOverlay,
  stopSenderServer,
} from '@/data/companion';
import { requestAllPillionPermissions } from '@/lib/permissions';
import { formatDurationMs } from '@/lib/time';
import { getStore, useAppState } from '@/state/app';
import {
  initCompanionBridge,
  refreshCompanionSnapshot,
  useCompanionState,
} from '@/state/companion';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { Segmented } from '@/ui/Segmented';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { UpdateCard } from '@/ui/shells/UpdateCard';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

export default function CockpitScreen() {
  const router = useRouter();
  const { settings } = useAppState();
  const companion = useCompanionState();
  const palette = getPalette(settings.theme);

  const badge = resolveLinkBadge(
    companion.connectionStatus,
    companion.isServerRunning,
    companion.isCallActive
  );

  const badgeColor =
    badge.tone === 'active'
      ? palette.accent
      : badge.tone === 'alert'
        ? palette.danger
        : badge.tone === 'linking'
          ? palette.warning
          : palette.foregroundMuted;

  const progress = clampProgress(companion.progressMs, companion.durationMs);
  const lowBattery = isBatteryLow(companion.batteryLevel);

  const handleModeChange = async (mode: RelayMode) => {
    await setRelayMode(getStore(), mode);
    await initCompanionBridge(mode === 'receiver');
  };

  const handleStartOverlay = async () => {
    const started = await startCockpitOverlay();
    if (!started) {
      showToast('Grant Display Over Apps permission for the floating bubble', 'info');
      await openOverlayPermissionSettings();
      return;
    }
    await refreshCompanionSnapshot();
    showToast('Floating cockpit overlay started', 'success');
  };

  const handleStopOverlay = async () => {
    await stopCockpitOverlay();
    await refreshCompanionSnapshot();
  };

  const handleStartServer = async () => {
    const granted = await requestAllPillionPermissions();
    if (!granted) {
      showToast('Bluetooth and phone permissions are required to relay alerts', 'error');
      return;
    }
    const snap = await refreshCompanionSnapshot();
    if (!snap.hasNotificationAccess) {
      showToast('Enable Notification Access for Pillion', 'info');
      await openNotificationAccessSettings();
      return;
    }
    await startSenderServer();
    await refreshCompanionSnapshot();
  };

  const handleConnectFlow = async () => {
    const granted = await requestAllPillionPermissions();
    if (!granted) {
      showToast('Bluetooth permission is required to list paired phones', 'error');
      return;
    }
    router.push('/connect');
  };

  return (
    <PageShell
      title="Pillion"
      eyebrow={settings.relayMode === 'receiver' ? 'HANDLEBAR DISPLAY' : 'POCKET RELAY'}
      rightSlot={
        <View className="flex-row items-center gap-2">
          <View
            style={{ borderColor: badgeColor }}
            className="flex-row items-center gap-1.5 rounded-xs border-[1.5px] bg-surface px-2.5 py-1.5"
          >
            <View
              style={{ backgroundColor: badgeColor }}
              className="h-2 w-2 rounded-full"
            />
            <Text style={{ color: badgeColor }} className="font-mono-bold text-xs">
              {badge.label}
            </Text>
          </View>
          <View className="flex-row items-center gap-1 rounded-xs border-[1.5px] border-border bg-surface px-2.5 py-1.5">
            <Icon
              name="battery"
              size={14}
              color={lowBattery ? palette.danger : palette.foreground}
            />
            <Text
              className={`font-mono-bold text-xs ${
                lowBattery ? 'text-danger' : 'text-foreground'
              }`}
            >
              {formatBatteryLabel(companion.batteryLevel)}
            </Text>
          </View>
        </View>
      }
    >
      <ScreenScroll>
        <Segmented
          options={[
            { value: 'receiver', label: 'Handlebar' },
            { value: 'sender', label: 'Pocket Sender' },
          ]}
          value={settings.relayMode}
          onChange={handleModeChange}
        />

        <UpdateCard />

        {settings.relayMode === 'receiver' ? (
          <View className="gap-4">
            {companion.isCallActive ? (
              <Card tone="alert" className="gap-4">
                <View className="flex-row items-center justify-between">
                  <View className="flex-row items-center gap-2">
                    <Icon name="phoneIncoming" size={20} color={palette.danger} />
                    <Text className="font-mono-bold text-xs uppercase tracking-widest text-danger">
                      {companion.isCallIncoming ? 'INCOMING CALL' : 'CALL ACTIVE'}
                    </Text>
                  </View>
                </View>
                <Text className="font-bold text-2xl text-foreground" numberOfLines={1}>
                  {companion.callerName || 'Unknown Caller'}
                </Text>
                <View className="flex-row gap-3">
                  <Button
                    label="Decline"
                    icon="phoneOff"
                    tone="danger"
                    onPress={() => sendCockpitCommand('/call/decline')}
                    className="flex-1"
                  />
                  {companion.isCallIncoming ? (
                    <Button
                      label="Answer"
                      icon="phoneIncoming"
                      tone="accent"
                      onPress={() => sendCockpitCommand('/call/answer')}
                      className="flex-1"
                    />
                  ) : null}
                </View>
              </Card>
            ) : null}

            {/* Asymmetric sunlight-readable Media Deck */}
            <Card className="gap-4">
              <View className="flex-row items-start justify-between gap-3">
                <View className="flex-1 gap-1">
                  <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                    NOW PLAYING
                  </Text>
                  <Text className="font-bold text-2xl text-foreground" numberOfLines={2}>
                    {companion.songTitle}
                  </Text>
                  <Text className="text-base text-foreground-muted" numberOfLines={1}>
                    {companion.artistName}
                  </Text>
                </View>
                {companion.durationMs > 0 ? (
                  <Text className="font-mono-bold text-sm text-primary">
                    {formatDurationMs(companion.progressMs)} /{' '}
                    {formatDurationMs(companion.durationMs)}
                  </Text>
                ) : null}
              </View>

              <View className="h-2 w-full overflow-hidden rounded-xs bg-surface-muted">
                <View
                  style={{ width: `${Math.round(progress * 100)}%` }}
                  className="h-full bg-primary"
                />
              </View>

              {/* Oversized glove-friendly asymmetric transport controls */}
              <View className="flex-row gap-2.5">
                <Press
                  onPress={() => sendCockpitCommand('/media/prev')}
                  accessibilityLabel="Previous track"
                  className="w-16 min-h-hit items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted"
                >
                  <Icon name="skipBack" size={24} color={palette.foreground} />
                </Press>
                <Press
                  onPress={() => sendCockpitCommand('/media/playpause')}
                  haptic="confirm"
                  accessibilityLabel={companion.isPlaying ? 'Pause' : 'Play'}
                  className="flex-1 min-h-hit flex-row items-center justify-center gap-2 rounded-sm border-[1.5px] border-primary bg-primary"
                >
                  <Icon
                    name={companion.isPlaying ? 'pause' : 'play'}
                    size={24}
                    color={palette.onPrimary}
                  />
                  <Text className="font-bold text-lg text-on-primary">
                    {companion.isPlaying ? 'PAUSE' : 'PLAY'}
                  </Text>
                </Press>
                <Press
                  onPress={() => sendCockpitCommand('/media/next')}
                  accessibilityLabel="Next track"
                  className="w-16 min-h-hit items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted"
                >
                  <Icon name="skipForward" size={24} color={palette.foreground} />
                </Press>
              </View>

              <View className="flex-row gap-2.5">
                <Button
                  label="Vol -"
                  icon="volumeMute"
                  tone="surface"
                  compact
                  onPress={() => sendCockpitCommand('/volume/down')}
                  className="flex-1"
                />
                <Button
                  label="Vol +"
                  icon="volume"
                  tone="surface"
                  compact
                  onPress={() => sendCockpitCommand('/volume/up')}
                  className="flex-1"
                />
              </View>
            </Card>

            {/* Bluetooth & Overlay Controls */}
            <Card className="gap-3">
              <View className="flex-row items-center justify-between">
                <View className="flex-row items-center gap-2">
                  <Icon
                    name={
                      companion.connectionStatus === 'Connected'
                        ? 'bluetoothConnected'
                        : 'bluetooth'
                    }
                    size={20}
                    color={
                      companion.connectionStatus === 'Connected'
                        ? palette.accent
                        : palette.primary
                    }
                  />
                  <Text className="font-bold text-base text-foreground">
                    {companion.connectionStatus}
                  </Text>
                </View>
                {companion.connectionStatus === 'Connected' ? (
                  <Press
                    onPress={() => disconnectCompanion()}
                    className="px-3 py-1.5 rounded-xs border-[1.5px] border-border"
                  >
                    <Text className="font-mono text-xs text-danger">DISCONNECT</Text>
                  </Press>
                ) : null}
              </View>

              <Button
                label={
                  companion.connectionStatus === 'Connected'
                    ? 'Switch Paired Phone'
                    : 'Connect Paired Phone'
                }
                icon="bluetooth"
                tone={companion.connectionStatus === 'Connected' ? 'surface' : 'primary'}
                onPress={handleConnectFlow}
              />

              {companion.isOverlayRunning ? (
                <Button
                  label="Stop Floating Overlay"
                  icon="layers"
                  tone="danger"
                  onPress={handleStopOverlay}
                />
              ) : (
                <Button
                  label="Launch Floating Overlay"
                  icon="layers"
                  tone="accent"
                  onPress={handleStartOverlay}
                />
              )}
            </Card>
          </View>
        ) : (
          <View className="gap-4">
            <Card className="gap-3">
              <View className="flex-row items-center justify-between">
                <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                  SPP SERVER STATUS
                </Text>
                <Text className="font-mono-bold text-xs text-primary">
                  {companion.isServerRunning ? 'BROADCASTING' : 'STOPPED'}
                </Text>
              </View>
              <Text className="font-bold text-xl text-foreground">
                {companion.connectionStatus}
              </Text>
              <Text className="text-sm text-foreground-muted">
                Keep this phone in your pocket. Once the handlebar phone connects, music, alerts, calls, and battery mirror automatically.
              </Text>

              {companion.isServerRunning ? (
                <Button
                  label="Stop Relay Server"
                  icon="bluetoothOff"
                  tone="danger"
                  onPress={async () => {
                    await stopSenderServer();
                    await refreshCompanionSnapshot();
                  }}
                />
              ) : (
                <Button
                  label="Start Relay Server"
                  icon="radio"
                  tone="primary"
                  onPress={handleStartServer}
                />
              )}
            </Card>

            <Card className="gap-3">
              <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                REQUIRED ACCESS
              </Text>
              <Button
                label={
                  companion.hasRuntimePermissions
                    ? 'Bluetooth & Call Access Granted'
                    : 'Grant Bluetooth & Call Access'
                }
                icon={companion.hasRuntimePermissions ? 'check' : 'shield'}
                tone={companion.hasRuntimePermissions ? 'surface' : 'primary'}
                compact
                onPress={async () => {
                  await requestAllPillionPermissions();
                  await refreshCompanionSnapshot();
                }}
              />
              <Button
                label={
                  companion.hasNotificationAccess
                    ? 'Notification Listener Active'
                    : 'Enable Notification Access'
                }
                icon={companion.hasNotificationAccess ? 'check' : 'bell'}
                tone={companion.hasNotificationAccess ? 'surface' : 'primary'}
                compact
                onPress={() => openNotificationAccessSettings()}
              />
            </Card>
          </View>
        )}
      </ScreenScroll>
    </PageShell>
  );
}

