import React from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { Tabs } from 'expo-router/js-tabs';
import { useCompanionState } from '@/state/companion';
import { Icon, type PillionIconName } from '@/ui/Icon';
import { usePalette } from '@/ui/ThemeContext';
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
  const palette = usePalette();
  const companion = useCompanionState();

  return (
    <View
      style={{ paddingBottom: Math.max(insets.bottom, 8) }}
      className="flex-row border-t-[1.5px] border-border bg-background px-2 pt-1.5 gap-1.5"
    >
      {state.routes.map((route, idx) => {
        const focused = state.index === idx;
        const meta = TAB_META[route.name] ?? { label: route.name, icon: 'radio' };
        const badgeCount =
          route.name === 'phone'
            ? companion.sessionMissedCalls
            : route.name === 'alerts'
              ? companion.notifications.length
              : 0;

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
            <View className="relative items-center justify-center">
              <Icon
                name={meta.icon}
                size={20}
                color={focused ? palette.primary : palette.foregroundMuted}
              />
              {badgeCount > 0 ? (
                <View className="absolute -top-1 -right-2.5 min-w-[14px] h-[14px] px-1 rounded-full bg-danger items-center justify-center">
                  <Text className="font-mono-bold text-[9px] text-white leading-none">
                    {badgeCount > 9 ? '9+' : badgeCount}
                  </Text>
                </View>
              ) : null}
            </View>
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
