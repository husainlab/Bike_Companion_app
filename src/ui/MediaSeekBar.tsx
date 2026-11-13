import React, { useState } from 'react';
import {
  GestureResponderEvent,
  LayoutChangeEvent,
  View,
} from 'react-native';

export interface MediaSeekBarProps {
  progressMs: number;
  durationMs: number;
  onSeek: (targetMs: number) => void;
  onSeekingChange?: (isSeeking: boolean, currentSeekMs: number) => void;
  disabled?: boolean;
}

export function MediaSeekBar({
  progressMs,
  durationMs,
  onSeek,
  onSeekingChange,
  disabled = false,
}: MediaSeekBarProps) {
  const [containerWidth, setContainerWidth] = useState(0);
  const [isSeeking, setIsSeeking] = useState(false);
  const [seekProgressMs, setSeekProgressMs] = useState(0);

  const calculateTargetMs = (touchX: number): number => {
    if (containerWidth <= 0 || durationMs <= 0) return 0;
    const clampedX = Math.max(0, Math.min(containerWidth, touchX));
    return Math.round((clampedX / containerWidth) * durationMs);
  };

  const handleTouchStart = (e: GestureResponderEvent) => {
    if (disabled || durationMs <= 0) return;
    const targetMs = calculateTargetMs(e.nativeEvent.locationX);
    setSeekProgressMs(targetMs);
    setIsSeeking(true);
    onSeekingChange?.(true, targetMs);
  };

  const handleTouchMove = (e: GestureResponderEvent) => {
    if (disabled || durationMs <= 0) return;
    const targetMs = calculateTargetMs(e.nativeEvent.locationX);
    setSeekProgressMs(targetMs);
    onSeekingChange?.(true, targetMs);
  };

  const handleTouchEnd = (e: GestureResponderEvent) => {
    if (disabled || durationMs <= 0) return;
    const targetMs = calculateTargetMs(e.nativeEvent.locationX);
    setIsSeeking(false);
    onSeekingChange?.(false, targetMs);
    onSeek(targetMs);
  };

  const handleTouchCancel = () => {
    if (disabled || durationMs <= 0) return;
    setIsSeeking(false);
    onSeekingChange?.(false, seekProgressMs);
  };

  const handleLayout = (e: LayoutChangeEvent) => {
    setContainerWidth(e.nativeEvent.layout.width);
  };

  const activeProgressMs = isSeeking ? seekProgressMs : progressMs;
  const ratio = durationMs > 0 ? Math.max(0, Math.min(1, activeProgressMs / durationMs)) : 0;
  const percent = Math.round(ratio * 100);

  return (
    <View
      onLayout={handleLayout}
      onStartShouldSetResponder={() => !disabled && durationMs > 0}
      onMoveShouldSetResponder={() => !disabled && durationMs > 0}
      onResponderGrant={handleTouchStart}
      onResponderMove={handleTouchMove}
      onResponderRelease={handleTouchEnd}
      onResponderTerminate={handleTouchCancel}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onTouchCancel={handleTouchCancel}
      accessibilityRole="adjustable"
      accessibilityLabel="Media playback progress"
      accessibilityValue={{ min: 0, max: durationMs, now: activeProgressMs }}
      className="py-2.5 w-full justify-center"
    >
      <View className="h-2 w-full overflow-hidden rounded-xs bg-surface-muted relative">
        <View
          style={{ width: `${percent}%` }}
          className={`h-full ${isSeeking ? 'bg-accent' : 'bg-primary'}`}
        />
      </View>
      {isSeeking ? (
        <View
          style={{ left: `${percent}%`, marginLeft: -5 }}
          className="absolute h-4 w-2.5 rounded-xs bg-foreground border border-primary pointer-events-none"
        />
      ) : null}
    </View>
  );
}
