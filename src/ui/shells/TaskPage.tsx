import React from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { DetailShell } from './DetailShell';
import { ScreenScroll } from './ScreenScroll';

export interface TaskPageProps {
  title: string;
  eyebrow?: string;
  footer?: React.ReactNode;
  children: React.ReactNode;
}

export function TaskPage({ title, eyebrow, footer, children }: TaskPageProps) {
  const insets = useSafeAreaInsets();
  return (
    <DetailShell title={title} eyebrow={eyebrow}>
      <ScreenScroll>{children}</ScreenScroll>
      {footer ? (
        <View
          style={{ paddingBottom: Math.max(insets.bottom, 16) }}
          className="border-t-[1.5px] border-border bg-background px-4 pt-3"
        >
          {footer}
        </View>
      ) : null}
    </DetailShell>
  );
}

