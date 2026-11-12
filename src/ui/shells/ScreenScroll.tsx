import React from 'react';
import { ScrollView, type ScrollViewProps } from 'react-native';

export interface ScreenScrollProps extends ScrollViewProps {
  className?: string;
}

export function ScreenScroll({
  className = '',
  contentContainerStyle,
  children,
  ...props
}: ScreenScrollProps) {
  return (
    <ScrollView
      {...props}
      className={`flex-1 bg-background ${className}`.trim()}
      contentContainerStyle={[
        { paddingHorizontal: 16, paddingTop: 16, paddingBottom: 32, gap: 16 },
        contentContainerStyle,
      ]}
      showsVerticalScrollIndicator={false}
    >
      {children}
    </ScrollView>
  );
}

