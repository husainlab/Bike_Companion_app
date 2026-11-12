import React from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { Header } from './Header';

export interface DetailShellProps {
  title: string;
  eyebrow?: string;
  rightSlot?: React.ReactNode;
  children: React.ReactNode;
}

export function DetailShell({ title, eyebrow, rightSlot, children }: DetailShellProps) {
  const router = useRouter();
  return (
    <View className="flex-1 bg-background">
      <Header
        title={title}
        eyebrow={eyebrow}
        onBack={() => router.back()}
        rightSlot={rightSlot}
      />
      <View className="flex-1">{children}</View>
    </View>
  );
}

