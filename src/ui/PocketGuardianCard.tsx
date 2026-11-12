import React from 'react';
import { View } from 'react-native';
import { evaluatePocketGuardian } from '@/core/guardian';
import { useAppState } from '@/state/app';
import type { CompanionSnapshot } from '@/state/companion';
import { Card } from './Card';
import { Icon } from './Icon';
import { getPalette } from './palette';
import { Text } from './Text';

export interface PocketGuardianCardProps {
  companion: Pick<CompanionSnapshot, 'batteryLevel' | 'batteryTempC'>;
  className?: string;
}

export function PocketGuardianCard({ companion, className }: PocketGuardianCardProps) {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);

  const status = evaluatePocketGuardian(companion.batteryLevel, companion.batteryTempC);
  if (!status.hasAlert) return null;

  const isCritical = status.tone === 'critical';
  const iconColor = isCritical ? palette.danger : palette.warning;
  const textColor = isCritical ? 'text-danger' : 'text-warning';

  return (
    <Card
      tone="alert"
      className={`border ${isCritical ? 'border-danger/60' : 'border-warning/60'} gap-2 py-3 px-4 ${className ?? ''}`}
    >
      <View className="flex-row items-center gap-2">
        <Icon
          name={status.isOverheating || status.isWarm ? 'alert' : 'battery'}
          size={18}
          color={iconColor}
        />
        <Text className={`font-mono-bold text-xs uppercase tracking-wider ${textColor} flex-1 min-w-0 break-words`}>
          {status.alertTitle}
        </Text>
      </View>
      <Text className="text-xs text-foreground-muted leading-relaxed break-words">
        {status.alertMessage}
      </Text>
    </Card>
  );
}

