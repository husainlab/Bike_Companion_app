import React from 'react';
import { Pressable, type PressableProps } from 'react-native';
import { haptics } from './haptics';

export interface PressProps extends PressableProps {
  className?: string;
  haptic?: 'tap' | 'confirm' | 'none';
}

export function Press({
  className = '',
  haptic = 'tap',
  onPress,
  disabled,
  children,
  ...props
}: PressProps) {
  return (
    <Pressable
      {...props}
      disabled={disabled}
      onPress={(e) => {
        if (!disabled && haptic !== 'none') {
          haptics[haptic]();
        }
        onPress?.(e);
      }}
      className={className}
      style={({ pressed }) => [
        {
          opacity: disabled ? 0.45 : pressed ? 0.78 : 1,
        },
        typeof props.style === 'function' ? props.style({ pressed, hovered: false }) : props.style,
      ]}
    >
      {children}
    </Pressable>
  );
}

