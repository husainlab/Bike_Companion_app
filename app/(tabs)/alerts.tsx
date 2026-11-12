import React from 'react';
import { View } from 'react-native';
import { clearRideHistory } from '@/actions/cockpit';
import { RIDING_QUICK_REPLIES } from '@/core/cockpit';
import {
  dismissMirroredNotification,
  replyToMirroredNotification,
} from '@/data/companion';
import { formatRelativeTime } from '@/lib/time';
import { loadCockpitOverview } from '@/queries/cockpit';
import { useScreen } from '@/screens/useScreen';
import { getStore, useAppState } from '@/state/app';
import { useCompanionState } from '@/state/companion';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

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
              <Card key={item.key} className="gap-2.5">
                <View className="flex-row items-center justify-between gap-2 min-w-0">
                  <Text className="font-bold text-base text-foreground flex-1 min-w-0 break-words" numberOfLines={1}>
                    {item.title}
                  </Text>
                  <Text className="font-mono text-xs text-foreground-muted shrink-0">
                    {formatRelativeTime(item.receivedAt)}
                  </Text>
                  <Press
                    onPress={() => dismissMirroredNotification(item.key)}
                    accessibilityLabel="Dismiss notification"
                    className="h-9 w-9 shrink-0 items-center justify-center rounded-xs border-[1.5px] border-border bg-surface-muted active:border-danger"
                  >
                    <Icon name="x" size={16} color={palette.foregroundMuted} />
                  </Press>
                </View>
                {item.text ? (
                  <Text className="text-sm text-foreground-muted leading-relaxed break-words" numberOfLines={3}>
                    {item.text}
                  </Text>
                ) : null}
                <View className="flex-row items-center justify-between gap-2 min-w-0">
                  <Text className="font-mono text-xs text-foreground-muted flex-1 min-w-0 break-words" numberOfLines={1}>
                    {item.packageName}
                  </Text>
                  {item.canReply ? (
                    <Text className="font-mono-bold text-xs text-accent shrink-0">QUICK REPLY</Text>
                  ) : null}
                </View>
                {item.canReply ? (
                  <View className="flex-row flex-wrap gap-2 pt-1">
                    {RIDING_QUICK_REPLIES.map((reply) => (
                      <Press
                        key={reply}
                        onPress={async () => {
                          await replyToMirroredNotification(item.key, reply);
                          showToast(`Sent: "${reply}"`, 'success');
                        }}
                        className="flex-row items-center gap-1.5 rounded-sm border-[1.5px] border-accent bg-surface-muted px-3 py-2"
                      >
                        <Icon name="send" size={14} color={palette.accent} />
                        <Text className="font-bold text-xs text-accent">{reply}</Text>
                      </Press>
                    ))}
                  </View>
                ) : null}
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
                    {ride.mode.toUpperCase()} • {ride.endedAt ? 'DONE' : 'LIVE'} •{' '}
                    {formatRelativeTime(ride.startedAt)}
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

