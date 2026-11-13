import React, { useState } from 'react';
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
  const [expandedKeys, setExpandedKeys] = useState<Set<string>>(new Set());

  return (
    <PageShell
      title="Alerts & Log"
      eyebrow="LIVE MIRROR FEED"
      rightSlot={
        companion.notifications.length > 0 ? (
          <Text className="font-mono-bold text-xs text-primary">
            {companion.notifications.length} ACTIVE
          </Text>
        ) : null
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
            companion.notifications.map((item) => {
              const msgList =
                item.messages && item.messages.length > 0
                  ? item.messages
                  : item.text
                    ? item.text.split('\n').filter(Boolean)
                    : [];
              const isMultiMessage = msgList.length > 1;
              const isExpanded = expandedKeys.has(item.key);

              return (
                <Card key={item.key} className="gap-3">
                  <View className="flex-row items-start justify-between gap-2 min-w-0">
                    <View className="flex-1 min-w-0 pr-1 gap-0.5">
                      <View className="flex-row items-center gap-2 flex-wrap">
                        <Text className="font-bold text-base text-foreground leading-snug" numberOfLines={2}>
                          {item.title}
                        </Text>
                        {isMultiMessage ? (
                          <View className="rounded-xs border border-accent bg-surface-muted px-1.5 py-0.5">
                            <Text className="font-mono text-[10px] text-accent">
                              {msgList.length} TEXTS
                            </Text>
                          </View>
                        ) : null}
                      </View>
                      <Text className="font-mono text-[11px] text-foreground-muted" numberOfLines={1}>
                        {item.packageName} • {formatRelativeTime(item.receivedAt)}
                      </Text>
                    </View>
                    <View className="flex-row items-center gap-1.5">
                      {isMultiMessage || (item.text && item.text.length > 70) ? (
                        <Press
                          onPress={() => {
                            setExpandedKeys((prev) => {
                              const next = new Set(prev);
                              if (next.has(item.key)) {
                                next.delete(item.key);
                              } else {
                                next.add(item.key);
                              }
                              return next;
                            });
                          }}
                          accessibilityLabel={isExpanded ? 'Collapse messages' : 'Expand all messages'}
                          className="h-9 px-2 shrink-0 flex-row items-center justify-center gap-1 rounded-xs border-[1.5px] border-border bg-surface-muted active:border-accent"
                        >
                          <Text className="font-mono-bold text-[11px] text-accent">
                            {isExpanded ? 'LESS' : `+${isMultiMessage ? msgList.length - 1 : 1} MORE`}
                          </Text>
                        </Press>
                      ) : null}
                      <Press
                        onPress={() => dismissMirroredNotification(item.key)}
                        accessibilityLabel="Dismiss notification"
                        className="h-9 w-9 shrink-0 items-center justify-center rounded-xs border-[1.5px] border-border bg-surface-muted active:border-danger"
                      >
                        <Icon name="x" size={16} color={palette.foregroundMuted} />
                      </Press>
                    </View>
                  </View>

                  {isExpanded ? (
                    <View className="gap-2 pt-1 border-t border-border">
                      {msgList.map((msg, idx) => (
                        <View key={idx} className="flex-row items-start gap-2">
                          {isMultiMessage ? (
                            <Text className="font-mono text-xs text-accent mt-0.5">{idx + 1}.</Text>
                          ) : null}
                          <Text className="flex-1 text-sm text-foreground leading-relaxed">{msg}</Text>
                        </View>
                      ))}
                    </View>
                  ) : item.text ? (
                    <Press
                      onPress={() => {
                        if (isMultiMessage || item.text.length > 70) {
                          setExpandedKeys((prev) => new Set(prev).add(item.key));
                        }
                      }}
                    >
                      <Text className="text-sm text-foreground-muted leading-relaxed" numberOfLines={2}>
                        {msgList[msgList.length - 1] ?? item.text}
                      </Text>
                    </Press>
                  ) : null}

                  {item.canReply ? (
                    <View className="pt-1 gap-1.5">
                      <Text className="font-mono-bold text-xs text-accent">QUICK REPLY</Text>
                      <View className="flex-row flex-wrap gap-2">
                        {RIDING_QUICK_REPLIES.map((reply) => (
                          <Press
                            key={reply}
                            onPress={async () => {
                              await replyToMirroredNotification(item.key, reply);
                              showToast(`Sent: "${reply}"`, 'success');
                            }}
                            className="flex-row items-center gap-1.5 rounded-sm border-[1.5px] border-accent bg-surface-muted px-3 py-2 min-h-10"
                          >
                            <Icon name="send" size={14} color={palette.accent} />
                            <Text className="font-bold text-xs text-accent">{reply}</Text>
                          </Press>
                        ))}
                      </View>
                    </View>
                  ) : null}
                </Card>
              );
            })
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

