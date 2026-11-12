import React from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useAppState } from '@/state/app';
import { Icon, type PillionIconName } from './Icon';
import { getPalette } from './palette';
import { Press } from './Press';
import { Text } from './Text';

export type ButtonTone = 'primary' | 'surface' | 'accent' | 'danger' | 'ghost';

export interface ButtonProps {
  label: string;
  onPress: () => void;
  tone?: ButtonTone;
  icon?: PillionIconName;
  disabled?: boolean;
  loading?: boolean;
  compact?: boolean;
  className?: string;
}

export function Button({
  label,
  onPress,
  tone = 'primary',
  icon,
  disabled = false,
  loading = false,
  compact = false,
  className = '',
}: ButtonProps) {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);

  const containerStyle =
    tone === 'primary'
      ? 'bg-primary border-primary'
      : tone === 'accent'
        ? 'bg-accent border-accent'
        : tone === 'danger'
          ? 'bg-danger border-danger'
          : tone === 'ghost'
            ? 'bg-transparent border-border'
            : 'bg-surface border-border';

  const textClass =
    tone === 'primary'
      ? 'text-on-primary'
      : tone === 'accent'
        ? 'text-on-accent'
        : tone === 'danger'
          ? 'text-white'
          : 'text-foreground';

  const iconColor =
    tone === 'primary'
      ? palette.onPrimary
      : tone === 'accent'
        ? palette.onAccent
        : tone === 'danger'
          ? '#FFFFFF'
          : palette.foreground;

  return (
    <Press
      onPress={onPress}
      disabled={disabled || loading}
      haptic={tone === 'primary' || tone === 'danger' ? 'confirm' : 'tap'}
      accessibilityRole="button"
      accessibilityLabel={label}
      className={`flex-row items-center justify-center rounded-sm border-[1.5px] ${
        compact ? 'min-h-12 px-4 py-2' : 'min-h-hit px-5 py-3.5'
      } ${containerStyle} ${className}`.trim()}
    >
      {loading ? (
        <ActivityIndicator size="small" color={iconColor} />
      ) : (
        <View className="flex-row items-center gap-2">
          {icon ? <Icon name={icon} size={compact ? 18 : 20} color={iconColor} /> : null}
          <Text className={`font-bold ${compact ? 'text-sm' : 'text-base'} ${textClass}`}>
            {label}
          </Text>
        </View>
      )}
    </Press>
  );
}

