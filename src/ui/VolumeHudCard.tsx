import React from 'react';
import { View } from 'react-native';
import { Icon } from '@/ui/Icon';
import { usePalette } from '@/ui/ThemeContext';
import { Text } from '@/ui/Text';

export interface VolumeHudCardProps {
  visible: boolean;
  percent: number;
  isAuto: boolean;
  speedKmh?: number;
}

export function VolumeHudCard({
  visible,
  percent,
  isAuto,
  speedKmh = 0,
}: VolumeHudCardProps) {
  const palette = usePalette();

  if (!visible) return null;

  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  const iconName = clamped === 0 ? 'volumeMute' : 'volume';

  return (
    <View className="rounded-sm border-[1.5px] border-primary/40 bg-surface px-3 py-2.5 gap-2">
      <View className="flex-row items-center justify-between">
        <View className="flex-row items-center gap-2">
          <Icon name={iconName} size={18} color={palette.primary} />
          <View>
            <Text className="font-mono-bold text-xs uppercase tracking-wider text-primary">
              {isAuto ? 'AUTO VOLUME' : 'VOLUME'}
            </Text>
            {isAuto ? (
              <Text className="font-mono text-[10px] text-foreground-muted">
                Compensating for {Math.round(speedKmh)} km/h wind
              </Text>
            ) : null}
          </View>
        </View>
        <Text className="font-mono-bold text-base text-primary">
          {clamped}%
        </Text>
      </View>
      <View className="h-2 w-full overflow-hidden rounded-xs bg-surface-muted">
        <View
          style={{ width: `${clamped}%` }}
          className="h-full bg-primary"
        />
      </View>
    </View>
  );
}

