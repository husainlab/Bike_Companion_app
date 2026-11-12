import React, { useSyncExternalStore } from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { haptics } from './haptics';
import { Text } from './Text';

export type ToastTone = 'info' | 'success' | 'error';

interface ToastMessage {
  id: number;
  text: string;
  tone: ToastTone;
}

let currentToast: ToastMessage | null = null;
let nextId = 1;
let hideTimer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<() => void>();

function emit(): void {
  for (const l of listeners) l();
}

export function showToast(text: string, tone: ToastTone = 'info'): void {
  if (tone === 'error') haptics.error();
  else if (tone === 'success') haptics.success();
  else haptics.tap();

  if (hideTimer) clearTimeout(hideTimer);
  const id = nextId++;
  currentToast = { id, text, tone };
  emit();

  hideTimer = setTimeout(() => {
    if (currentToast?.id === id) {
      currentToast = null;
      emit();
    }
  }, 3200);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function getSnapshot(): ToastMessage | null {
  return currentToast;
}

export function ToastHost() {
  const toast = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const insets = useSafeAreaInsets();

  if (!toast) return null;

  const borderClass =
    toast.tone === 'error'
      ? 'border-danger'
      : toast.tone === 'success'
        ? 'border-accent'
        : 'border-primary';

  return (
    <View
      pointerEvents="none"
      style={{ top: insets.top + 12 }}
      className="absolute left-4 right-4 z-50"
    >
      <View className={`rounded-sm border-[1.5px] bg-surface px-4 py-3 ${borderClass}`}>
        <Text className="font-bold text-sm text-foreground">{toast.text}</Text>
      </View>
    </View>
  );
}

