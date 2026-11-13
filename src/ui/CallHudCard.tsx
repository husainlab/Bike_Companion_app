import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { sendCockpitCommand } from '@/data/companion';
import { formatCallElapsed } from '@/lib/time';
import { useAppState } from '@/state/app';
import type { CompanionSnapshot } from '@/state/companion';
import { Button } from './Button';
import { Card } from './Card';
import { Icon } from './Icon';
import { getPalette } from './palette';
import { Text } from './Text';

export interface CallHudCardProps {
  companion: Pick<
    CompanionSnapshot,
    'isCallActive' | 'isCallIncoming' | 'isCallMuted' | 'callStartedAtMs' | 'callerName' | 'callerNumber'
  >;
  className?: string;
}

export function CallHudCard({ companion, className }: CallHudCardProps) {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const [now, setNow] = useState(() => Date.now());

  const isLiveCall = companion.isCallActive && !companion.isCallIncoming;

  useEffect(() => {
    if (!isLiveCall) return;
    const interval = setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => clearInterval(interval);
  }, [isLiveCall, companion.callStartedAtMs]);

  if (!companion.isCallActive) return null;

  const callerDisplay = companion.callerName || companion.callerNumber || 'Unknown Caller';
  const showNumber = companion.callerNumber && companion.callerName !== companion.callerNumber;
  const elapsed = isLiveCall ? formatCallElapsed(companion.callStartedAtMs, now) : '00:00';

  return (
    <Card tone="alert" className={`gap-4 ${className ?? ''}`}>
      <View className="flex-row items-center justify-between gap-2">
        <View className="flex-row items-center gap-2 flex-1 min-w-0">
          <Icon
            name={companion.isCallIncoming ? 'phoneIncoming' : companion.isCallMuted ? 'micOff' : 'phoneCall'}
            size={20}
            color={companion.isCallIncoming ? palette.danger : companion.isCallMuted ? palette.accent : palette.primary}
          />
          <View className="flex-1 min-w-0">
            <Text
              className={`font-mono-bold text-xs uppercase tracking-widest ${
                companion.isCallIncoming
                  ? 'text-danger'
                  : companion.isCallMuted
                    ? 'text-accent'
                    : 'text-primary'
              }`}
              numberOfLines={1}
            >
              {companion.isCallIncoming
                ? 'INCOMING CALL'
                : companion.isCallMuted
                  ? 'CALL MUTED'
                  : 'ACTIVE CALL'}
            </Text>
            <Text className="font-mono text-[10px] text-foreground-muted" numberOfLines={1}>
              AUDIO ON POCKET PHONE / INTERCOM
            </Text>
          </View>
        </View>

        {isLiveCall ? (
          <View className="rounded border border-border bg-surface-muted px-2.5 py-1 shrink-0">
            <Text className="font-mono-bold text-xs text-primary">{elapsed}</Text>
          </View>
        ) : null}
      </View>

      <View className="gap-0.5 min-w-0">
        <Text className="font-bold text-2xl text-foreground leading-tight" numberOfLines={2}>
          {callerDisplay}
        </Text>
        {showNumber ? (
          <Text className="font-mono text-sm text-foreground-muted" numberOfLines={1}>
            {companion.callerNumber}
          </Text>
        ) : null}
      </View>

      <View className="flex-row gap-3 pt-1">
        <Button
          label={companion.isCallIncoming ? 'Decline' : 'End Call'}
          icon="phoneOff"
          tone="danger"
          onPress={() => sendCockpitCommand('/call/decline')}
          className="flex-1 min-h-[56px]"
        />

        {companion.isCallIncoming ? (
          <Button
            label="Answer"
            icon="phoneIncoming"
            tone="accent"
            onPress={() => sendCockpitCommand('/call/answer')}
            className="flex-1 min-h-[56px]"
          />
        ) : (
          <Button
            label={companion.isCallMuted ? 'Muted' : 'Mute'}
            icon={companion.isCallMuted ? 'micOff' : 'mic'}
            tone={companion.isCallMuted ? 'accent' : 'surface'}
            onPress={() => sendCockpitCommand('/call/mute')}
            className="flex-1 min-h-[56px]"
          />
        )}
      </View>
    </Card>
  );
}
