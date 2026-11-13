import React, { useState } from 'react';
import { View, useWindowDimensions } from 'react-native';
import { useRouter } from 'expo-router';
import {
  endActiveRideSession,
  setRelayMode,
} from '@/actions/cockpit';
import {
  filterContacts,
  formatBatteryLabel,
  isBatteryLow,
  resolveLinkBadge,
} from '@/core/cockpit';
import type { RelayMode } from '@/core/types';
import {
  dialContactNumber,
  disconnectCompanion,
  launchLocalGoogleMaps,
  openNotificationAccessSettings,
  openOverlayPermissionSettings,
  seekMediaPosition,
  sendCockpitCommand,
  startCockpitOverlay,
  startSenderServer,
  stopCockpitOverlay,
  stopSenderServer,
  syncCompanionContacts,
} from '@/data/companion';
import { requestAllPillionPermissions } from '@/lib/permissions';
import { formatDurationMs } from '@/lib/time';
import { getStore, useAppState } from '@/state/app';
import {
  initCompanionBridge,
  refreshCompanionSnapshot,
  showVolumeHud,
  teardownPreviousRelayMode,
  useCompanionState,
  useVolumeHud,
} from '@/state/companion';
import { Button } from '@/ui/Button';
import { CallHudCard } from '@/ui/CallHudCard';
import { Card } from '@/ui/Card';
import { PocketGuardianCard } from '@/ui/PocketGuardianCard';
import { Icon } from '@/ui/Icon';
import { MediaSeekBar } from '@/ui/MediaSeekBar';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { Segmented } from '@/ui/Segmented';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { UpdateCard } from '@/ui/shells/UpdateCard';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';
import { VolumeHudCard } from '@/ui/VolumeHudCard';

export default function CockpitScreen() {
  const router = useRouter();
  const { width } = useWindowDimensions();
  const isWide = width >= 700;
  const { settings } = useAppState();
  const companion = useCompanionState();
  const volumeHud = useVolumeHud();
  const palette = getPalette(settings.theme);

  const [seekingInfo, setSeekingInfo] = useState<{ isSeeking: boolean; seekMs: number }>({
    isSeeking: false,
    seekMs: 0,
  });

  const favoriteContacts = filterContacts(companion.contacts, '').slice(0, 4);

  const handleVolumeAdjust = async (direction: 'up' | 'down') => {
    const cur = companion.volumePercent || 70;
    const next = direction === 'up' ? Math.min(100, cur + 7) : Math.max(0, cur - 7);
    showVolumeHud(next, false);
    await sendCockpitCommand(direction === 'up' ? '/volume/up' : '/volume/down');
  };

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

  const lowBattery = isBatteryLow(companion.batteryLevel);

  const handleModeChange = async (mode: RelayMode) => {
    if (mode === settings.relayMode) return;
    const prev = settings.relayMode;
    await setRelayMode(getStore(), mode);
    await teardownPreviousRelayMode(prev);
    await initCompanionBridge(mode);
    await refreshCompanionSnapshot();
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
    const granted = await requestAllPillionPermissions('sender');
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
    const granted = await requestAllPillionPermissions('receiver');
    if (!granted) {
      showToast('Bluetooth permission is required to list paired phones', 'error');
      return;
    }
    router.push('/connect');
  };

  const handleLaunchMaps = async (splitScreen: boolean) => {
    const snap = await refreshCompanionSnapshot();
    if (!snap.hasOverlayPermission) {
      showToast('Grant Display Over Apps permission so the widget floats over Google Maps', 'info');
      await openOverlayPermissionSettings();
      return;
    }
    const launched = await launchLocalGoogleMaps(splitScreen);
    await refreshCompanionSnapshot();
    if (!launched) {
      showToast('Google Maps is not installed on this handlebar phone', 'error');
    }
  };

  return (
    <PageShell
      title="Pillion"
      eyebrow={settings.relayMode === 'receiver' ? 'HANDLEBAR DISPLAY' : 'POCKET RELAY'}
      rightSlot={
        <View className="flex-row items-center gap-1.5 rounded-xs border-[1.5px] border-border bg-surface px-2.5 py-1.5 shrink-0">
          <View
            style={{ backgroundColor: badgeColor }}
            className="h-2 w-2 rounded-full"
          />
          <Text style={{ color: badgeColor }} className="font-mono-bold text-xs">
            {badge.label}
          </Text>
          <Text className="font-mono text-xs text-foreground-muted">•</Text>
          <Icon
            name="battery"
            size={13}
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
            <CallHudCard companion={companion} />
            <PocketGuardianCard companion={companion} />

            <VolumeHudCard
              visible={volumeHud.visible}
              percent={volumeHud.percent}
              isAuto={volumeHud.isAuto}
              speedKmh={companion.currentSpeedKmh}
            />

            <View className={isWide ? 'flex-row items-start gap-4' : 'gap-4'}>
              {/* Asymmetric sunlight-readable Media Deck */}
              <Card className={isWide ? 'flex-1 gap-4' : 'gap-4'}>
                <View className="gap-2">
                  <View className="flex-row items-center justify-between gap-2">
                    <View className="flex-row items-center gap-2 flex-wrap flex-1 min-w-0">
                      <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                        NOW PLAYING
                      </Text>
                      {settings.autoVolumeEnabled ? (
                        <View className="rounded border border-primary/30 bg-primary/10 px-1.5 py-0.5">
                          <Text className="font-mono text-[10px] text-primary">
                            AUTO VOL • {Math.round(companion.currentSpeedKmh)} KM/H
                          </Text>
                        </View>
                      ) : null}
                    </View>
                    {companion.durationMs > 0 ? (
                      <Text
                        className={`font-mono-bold text-sm shrink-0 ${
                          seekingInfo.isSeeking ? 'text-accent' : 'text-primary'
                        }`}
                      >
                        {formatDurationMs(
                          seekingInfo.isSeeking ? seekingInfo.seekMs : companion.progressMs
                        )}{' '}
                        / {formatDurationMs(companion.durationMs)}
                      </Text>
                    ) : null}
                  </View>

                  <View className="gap-0.5 min-w-0">
                    <Text className="font-bold text-2xl text-foreground leading-tight" numberOfLines={2}>
                      {companion.songTitle}
                    </Text>
                    <Text className="text-base text-foreground-muted leading-snug" numberOfLines={2}>
                      {companion.artistName}
                    </Text>
                  </View>
                </View>

                <MediaSeekBar
                  progressMs={companion.progressMs}
                  durationMs={companion.durationMs}
                  onSeek={(targetMs) => {
                    void seekMediaPosition(targetMs);
                  }}
                  onSeekingChange={(isSeeking, currentSeekMs) => {
                    setSeekingInfo({ isSeeking, seekMs: currentSeekMs });
                  }}
                />

                {/* Oversized glove-friendly asymmetric transport controls */}
                <View className="flex-row gap-2.5">
                  <Press
                    onPress={() => sendCockpitCommand('/media/prev')}
                    accessibilityLabel="Previous track"
                    className="w-16 min-h-[64px] items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted active:border-primary"
                  >
                    <Icon name="skipBack" size={24} color={palette.foreground} />
                  </Press>
                  <Press
                    onPress={() => sendCockpitCommand('/media/playpause')}
                    haptic="confirm"
                    accessibilityLabel={companion.isPlaying ? 'Pause' : 'Play'}
                    className="flex-1 min-h-[64px] flex-row items-center justify-center gap-2 rounded-sm border-[1.5px] border-primary bg-primary active:opacity-90"
                  >
                    <Icon
                      name={companion.isPlaying ? 'pause' : 'play'}
                      size={24}
                      color={palette.onPrimary}
                    />
                    <Text className="font-bold text-lg text-on-primary tracking-wide">
                      {companion.isPlaying ? 'PAUSE' : 'PLAY'}
                    </Text>
                  </Press>
                  <Press
                    onPress={() => sendCockpitCommand('/media/next')}
                    accessibilityLabel="Next track"
                    className="w-16 min-h-[64px] items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted active:border-primary"
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
                    onPress={() => handleVolumeAdjust('down')}
                    className="flex-1"
                  />
                  <Button
                    label="Vol +"
                    icon="volume"
                    tone="surface"
                    compact
                    onPress={() => handleVolumeAdjust('up')}
                    className="flex-1"
                  />
                </View>
              </Card>

              <View className={isWide ? 'flex-1 gap-4' : 'gap-4'}>
                {/* Local Google Maps + Floating Widget Mode */}
                <Card className="gap-3">
                  <View className="flex-row items-center justify-between gap-2">
                    <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                      LOCAL GOOGLE MAPS + WIDGET
                    </Text>
                    <Icon name="navigation" size={16} color={palette.primary} />
                  </View>
                  <Text className="text-sm text-foreground-muted">
                    Runs Google Maps locally while Pillion floats media, call controls & quick-replies on top. All audio stays on your pocket phone.
                  </Text>
                  <View className="flex-row gap-2.5">
                    <Button
                      label="Maps + Widget"
                      icon="navigation"
                      tone="primary"
                      compact
                      onPress={() => handleLaunchMaps(false)}
                      className="flex-1"
                    />
                    <Button
                      label="Split-Screen"
                      icon="columns"
                      tone="surface"
                      compact
                      onPress={() => handleLaunchMaps(true)}
                      className="flex-1"
                    />
                  </View>
                </Card>

                {/* Quick-Dial Starred Contacts */}
                <Card className="gap-3">
                  <View className="flex-row items-center justify-between">
                    <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                      QUICK DIAL
                    </Text>
                    <Press
                      onPress={() => router.push('/(tabs)/phone')}
                      className="px-2.5 py-1 rounded-xs border-[1.5px] border-border bg-surface-muted"
                    >
                      <Text className="font-mono-bold text-xs text-primary">OPEN PHONE</Text>
                    </Press>
                  </View>
                  {favoriteContacts.length === 0 ? (
                    <Text className="text-sm text-foreground-muted">
                      Connect to Pocket Phone to sync contacts & recent calls over Bluetooth.
                    </Text>
                  ) : (
                    <View className="flex-row flex-wrap gap-2">
                      {favoriteContacts.map((contact) => (
                        <Press
                          key={contact.id}
                          onPress={async () => {
                            await dialContactNumber(contact.phoneNumber);
                            showToast(`Dialing ${contact.name} on Pocket Phone`, 'success');
                          }}
                          haptic="confirm"
                          className="min-w-[46%] flex-1 flex-row items-center gap-2.5 rounded-sm border-[1.5px] border-border bg-surface-muted px-3 py-3 min-h-[58px]"
                        >
                          <View className="h-8 w-8 rounded-full items-center justify-center bg-accent/15 border border-accent/40 shrink-0">
                            <Icon name="phoneCall" size={14} color={palette.accent} />
                          </View>
                          <View className="flex-1 min-w-0">
                            <Text className="font-bold text-sm text-foreground leading-tight" numberOfLines={2}>
                              {contact.name}
                            </Text>
                          </View>
                        </Press>
                      ))}
                    </View>
                  )}
                </Card>
              </View>
            </View>

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
                    onPress={async () => {
                      await disconnectCompanion();
                      await endActiveRideSession(getStore()).catch(() => {});
                    }}
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
              <Button
                label="Sync Contacts & Recents"
                icon="users"
                tone="surface"
                compact
                onPress={async () => {
                  await syncCompanionContacts();
                  await refreshCompanionSnapshot();
                  showToast('Synced contacts & recent calls to handlebar', 'success');
                }}
              />
            </Card>
          </View>
        )}
      </ScreenScroll>
    </PageShell>
  );
}

