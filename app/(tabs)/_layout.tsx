import React from 'react';
import { Tabs } from 'expo-router/js-tabs';
import { TabBar } from '@/ui/shells/TabBar';

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{ headerShown: false }}
      tabBar={(props) => <TabBar {...props} />}
    >
      <Tabs.Screen name="index" options={{ title: 'Cockpit' }} />
      <Tabs.Screen name="alerts" options={{ title: 'Alerts' }} />
      <Tabs.Screen name="settings" options={{ title: 'Setup' }} />
    </Tabs>
  );
}
