export type SpeedVolumeAction = 'none' | 'volume_up' | 'volume_down';

export interface SpeedVolumeTransitionResult {
  action: SpeedVolumeAction;
  targetBracket: number;
  steps: number;
  shouldUpdateBracket: boolean;
}

/**
 * Maps motorcycle speed (in km/h) to volume compensation brackets:
 * 0: Stopped / Parking (< 15 km/h)
 * 1: City / Urban (15 to 49.9 km/h)
 * 2: Open Road / Cruising (50 to 84.9 km/h)
 * 3: Highway / Fast (>= 85 km/h)
 */
export function getSpeedVolumeBracket(speedKmh: number): number {
  if (!Number.isFinite(speedKmh) || speedKmh < 15) return 0;
  if (speedKmh < 50) return 1;
  if (speedKmh < 85) return 2;
  return 3;
}

/**
 * Pure evaluation of speed-sensitive volume adjustments with anti-flutter hysteresis.
 * Zero I/O, zero React imports, fully deterministic.
 */
export function evaluateSpeedVolumeTransition(params: {
  speedKmh: number;
  currentBracket: number;
  lastTransitionAtMs: number;
  nowMs: number;
  isCallActive: boolean;
  enabled: boolean;
  hysteresisMs?: number;
}): SpeedVolumeTransitionResult {
  const {
    speedKmh,
    currentBracket,
    lastTransitionAtMs,
    nowMs,
    isCallActive,
    enabled,
    hysteresisMs = 3000,
  } = params;

  // Never adjust volume automatically while disabled or during an active phone call
  if (!enabled || isCallActive) {
    return {
      action: 'none',
      targetBracket: currentBracket,
      steps: 0,
      shouldUpdateBracket: false,
    };
  }

  const targetBracket = getSpeedVolumeBracket(speedKmh);
  if (targetBracket === currentBracket) {
    return {
      action: 'none',
      targetBracket: currentBracket,
      steps: 0,
      shouldUpdateBracket: false,
    };
  }

  // Anti-flutter hysteresis: require the speed to be held steady for hysteresisMs
  if (nowMs - lastTransitionAtMs < hysteresisMs) {
    return {
      action: 'none',
      targetBracket: currentBracket,
      steps: 0,
      shouldUpdateBracket: false,
    };
  }

  if (targetBracket > currentBracket) {
    return {
      action: 'volume_up',
      targetBracket,
      steps: targetBracket - currentBracket,
      shouldUpdateBracket: true,
    };
  }

  return {
    action: 'volume_down',
    targetBracket,
    steps: currentBracket - targetBracket,
    shouldUpdateBracket: true,
  };
}

