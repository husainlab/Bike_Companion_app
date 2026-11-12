import React from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAppState } from '@/state/app';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { PillionMark } from '@/ui/PillionMark';
import { Press } from '@/ui/Press';
import { Text } from '@/ui/Text';

export interface HeaderProps {
  title: string;
  eyebrow?: string;
  onBack?: () => void;
  rightSlot?: React.ReactNode;
}

export function Header({ title, eyebrow, onBack, rightSlot }: HeaderProps) {
  const insets = useSafeAreaInsets();
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);

  return (
    <View
      style={{ paddingTop: insets.top + 8 }}
      className="border-b-[1.5px] border-border bg-background px-4 pb-3"
    >
      <View className="flex-row items-center justify-between gap-3">
        <View className="flex-row items-center gap-3 flex-1">
          {onBack ? (
            <Press
              onPress={onBack}
              accessibilityRole="button"
              accessibilityLabel="Go back"
              className="h-12 w-12 items-center justify-center rounded-sm border-[1.5px] border-border bg-surface"
            >
              <Icon name="back" size={20} color={palette.foreground} />
            </Press>
          ) : (
            <View className="h-10 w-10 items-center justify-center rounded-sm border-[1.5px] border-border bg-surface">
              <PillionMark size={24} color={palette.primary} />
            </View>
          )}
          <View className="flex-1">
            {eyebrow ? (
              <Text className="font-mono text-xs uppercase tracking-widest text-foreground-muted">
                {eyebrow}
              </Text>
            ) : null}
            <Text className="font-bold text-xl text-foreground" numberOfLines={1}>
              {title}
            </Text>
          </View>
        </View>
        {rightSlot ? <View>{rightSlot}</View> : null}
      </View>
    </View>
  );
}

