import React from 'react';
import { View } from 'react-native';
import { clearRideHistory } from '@/actions/cockpit';
import { formatRelativeTime } from '@/lib/time';
import { loadCockpitOverview } from '@/queries/cockpit';
import { useScreen } from '@/screens/useScreen';
import { getStore, useAppState } from '@/state/app';
import { useCompanionState } from '@/state/companion';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { Text } from '@/ui/Text';

export default function AlertsScreen() {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const companion = useCompanionState();
  const { data } = useScreen('alerts-overview', [], loadCockpitOverview);

  return (
    <PageShell
      title="Alerts & Log"
      eyebrow="LIVE MIRROR FEED"
      rightSlot={
        <Text className="font-mono-bold text-xs text-primary">
          {companion.notifications.length} ACTIVE
        </Text>
      }
    >
      <ScreenScroll>
        <View className="gap-3">
          <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
            INCOMING NOTIFICATIONS
          </Text>

          {companion.notifications.length === 0 ? (
            <Card tone="muted" className="items-start gap-2">
              <Icon name="bell" size={20} color={palette.foregroundMuted} />
              <Text className="font-bold text-base text-foreground">
                No active alerts mirrored
              </Text>
              <Text className="text-sm text-foreground-muted">
                Notifications posted on your pocket phone appear here and on the floating handlebar bubble.
              </Text>
            </Card>
          ) : (
            companion.notifications.map((item) => (
              <Card key={item.key} className="gap-1.5">
                <View className="flex-row items-center justify-between gap-2">
                  <Text className="font-bold text-base text-foreground flex-1" numberOfLines={1}>
                    {item.title}
                  </Text>
                  <Text className="font-mono text-xs text-foreground-muted">
                    {formatRelativeTime(item.receivedAt)}
                  </Text>
                </View>
                {item.text ? (
                  <Text className="text-sm text-foreground-muted" numberOfLines={3}>
                    {item.text}
                  </Text>
                ) : null}
                <Text className="font-mono text-xs text-foreground-muted">
                  {item.packageName}
                </Text>
              </Card>
            ))
          )}
        </View>

        <View className="gap-3 pt-2">
          <View className="flex-row items-center justify-between">
            <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
              RECENT RIDE LINKS
            </Text>
            {(data?.recentRides.length ?? 0) > 0 ? (
              <Button
                label="Clear"
                tone="ghost"
                compact
                onPress={() => clearRideHistory(getStore())}
              />
            ) : null}
          </View>

          {!data || data.recentRides.length === 0 ? (
            <Card tone="muted">
              <Text className="text-sm text-foreground-muted">
                Connected ride sessions will be logged locally in SQLite.
              </Text>
            </Card>
          ) : (
            data.recentRides.map((ride) => (
              <Card key={ride.id} className="flex-row items-center justify-between">
                <View className="gap-0.5">
                  <Text className="font-bold text-base text-foreground">{ride.deviceName}</Text>
                  <Text className="font-mono text-xs text-foreground-muted">
                    {ride.mode.toUpperCase()} • {formatRelativeTime(ride.startedAt)}
                  </Text>
                </View>
                <Text className="font-mono-bold text-sm text-primary">
                  {ride.notificationsRelayed} alerts
                </Text>
              </Card>
            ))
          )}
        </View>
      </ScreenScroll>
    </PageShell>
  );
}

