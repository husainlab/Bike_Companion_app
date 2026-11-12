import React from 'react';
import { View, type ViewProps } from 'react-native';

export interface CardProps extends ViewProps {
  className?: string;
  tone?: 'surface' | 'muted' | 'alert';
}

export function Card({ className = '', tone = 'surface', children, ...props }: CardProps) {
  const toneClass =
    tone === 'muted'
      ? 'bg-surface-muted border-border'
      : tone === 'alert'
        ? 'bg-surface border-danger'
        : 'bg-surface border-border';

  return (
    <View
      {...props}
      className={`rounded-md border-[1.5px] p-4 ${toneClass} ${className}`.trim()}
    >
      {children}
    </View>
  );
}

