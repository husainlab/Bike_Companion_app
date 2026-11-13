import React from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { Tabs } from 'expo-router/js-tabs';
import { useAppState } from '@/state/app';
import { Icon, type PillionIconName } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { Text } from '@/ui/Text';

const TAB_META: Record<string, { label: string; icon: PillionIconName }> = {
  index: { label: 'Cockpit', icon: 'smartphone' },
  phone: { label: 'Phone', icon: 'phone' },
  alerts: { label: 'Alerts', icon: 'bell' },
  settings: { label: 'Setup', icon: 'settings' },
};

type TabBarProps = Parameters<NonNullable<React.ComponentProps<typeof Tabs>['tabBar']>>[0];

export function TabBar({ state, emitter, navigateToTab }: TabBarProps) {
  const insets = useSafeAreaInsets();
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);

  return (
    <View
      style={{ paddingBottom: Math.max(insets.bottom, 8) }}
      className="flex-row border-t-[1.5px] border-border bg-background px-2 pt-1.5 gap-1.5"
    >
      {state.routes.map((route, idx) => {
        const focused = state.index === idx;
        const meta = TAB_META[route.name] ?? { label: route.name, icon: 'radio' };

        return (
          <Press
            key={route.key}
            onPress={() => {
              const event = emitter.emit({
                type: 'tabPress',
                target: route.key,
                canPreventDefault: true,
              });
              if (!focused && !event.defaultPrevented) {
                navigateToTab(route.key);
              }
            }}
            accessibilityRole="tab"
            accessibilityState={{ selected: focused }}
            className={`flex-1 flex-col items-center justify-center py-2 px-1 rounded-sm border-[1.5px] min-h-[58px] gap-1 ${
              focused
                ? 'border-primary bg-surface'
                : 'border-transparent bg-transparent'
            }`}
          >
            <Icon
              name={meta.icon}
              size={20}
              color={focused ? palette.primary : palette.foregroundMuted}
            />
            <Text
              numberOfLines={1}
              className={`font-bold text-xs text-center tracking-tight ${
                focused ? 'text-primary' : 'text-foreground-muted'
              }`}
            >
              {meta.label}
            </Text>
          </Press>
        );
      })}
    </View>
  );
}
