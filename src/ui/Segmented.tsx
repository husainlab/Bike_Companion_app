import React from 'react';
import { View } from 'react-native';
import { Press } from './Press';
import { Text } from './Text';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
}

export interface SegmentedProps<T extends string> {
  options: readonly SegmentedOption<T>[];
  value: T;
  onChange: (next: T) => void;
  className?: string;
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
  className = '',
}: SegmentedProps<T>) {
  return (
    <View
      className={`flex-row rounded-sm border-[1.5px] border-border bg-surface p-1 ${className}`.trim()}
    >
      {options.map((option) => {
        const active = option.value === value;
        return (
          <Press
            key={option.value}
            onPress={() => onChange(option.value)}
            accessibilityRole="tab"
            accessibilityState={{ selected: active }}
            className={`flex-1 items-center justify-center rounded-xs min-h-12 px-3 ${
              active ? 'bg-primary' : 'bg-transparent'
            }`}
          >
            <Text
              className={`font-bold text-sm uppercase tracking-wider ${
                active ? 'text-on-primary' : 'text-foreground-muted'
              }`}
            >
              {option.label}
            </Text>
          </Press>
        );
      })}
    </View>
  );
}

