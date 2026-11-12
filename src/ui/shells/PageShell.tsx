import React from 'react';
import { View } from 'react-native';
import { Header } from './Header';

export interface PageShellProps {
  title: string;
  eyebrow?: string;
  rightSlot?: React.ReactNode;
  children: React.ReactNode;
}

export function PageShell({ title, eyebrow, rightSlot, children }: PageShellProps) {
  return (
    <View className="flex-1 bg-background">
      <Header title={title} eyebrow={eyebrow} rightSlot={rightSlot} />
      <View className="flex-1">{children}</View>
    </View>
  );
}

