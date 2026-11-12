import React from 'react';
import { Pressable, Text as RNText, View } from 'react-native';
import type { ErrorBoundaryProps } from 'expo-router';

/**
 * Minimal error boundary using only core RN primitives so recovery works even
 * if theme providers or custom fonts fail.
 */
export function ScreenError({ error, retry }: ErrorBoundaryProps) {
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: '#000000',
        justifyContent: 'center',
        padding: 24,
      }}
    >
      <RNText
        style={{
          color: '#FF453A',
          fontSize: 13,
          fontWeight: '700',
          letterSpacing: 1.5,
          marginBottom: 8,
        }}
      >
        SCREEN FAULT
      </RNText>
      <RNText
        style={{
          color: '#FFFFFF',
          fontSize: 22,
          fontWeight: '700',
          marginBottom: 12,
        }}
      >
        Cockpit view hit an error
      </RNText>
      <RNText
        style={{
          color: '#B5BAB3',
          fontSize: 14,
          marginBottom: 24,
        }}
      >
        {error.message || 'Unexpected error'}
      </RNText>
      <Pressable
        onPress={retry}
        style={{
          backgroundColor: '#FF5A1F',
          borderRadius: 6,
          paddingVertical: 14,
          paddingHorizontal: 20,
          alignItems: 'center',
        }}
      >
        <RNText style={{ color: '#000000', fontWeight: '700', fontSize: 16 }}>
          Reload View
        </RNText>
      </Pressable>
    </View>
  );
}

