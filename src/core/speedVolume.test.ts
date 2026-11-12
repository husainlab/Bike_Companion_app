import { describe, expect, it } from 'vitest';
import { evaluateSpeedVolumeTransition, getSpeedVolumeBracket } from './speedVolume';

describe('speedVolume core domain', () => {
  it('maps speeds to brackets correctly', () => {
    expect(getSpeedVolumeBracket(0)).toBe(0);
    expect(getSpeedVolumeBracket(14.9)).toBe(0);
    expect(getSpeedVolumeBracket(15)).toBe(1);
    expect(getSpeedVolumeBracket(49.9)).toBe(1);
    expect(getSpeedVolumeBracket(50)).toBe(2);
    expect(getSpeedVolumeBracket(84.9)).toBe(2);
    expect(getSpeedVolumeBracket(85)).toBe(3);
    expect(getSpeedVolumeBracket(120)).toBe(3);
    expect(getSpeedVolumeBracket(Number.NaN)).toBe(0);
  });

  it('suppresses transitions when disabled or during active phone calls', () => {
    const disabled = evaluateSpeedVolumeTransition({
      speedKmh: 95,
      currentBracket: 0,
      lastTransitionAtMs: 1000,
      nowMs: 5000,
      isCallActive: false,
      enabled: false,
    });
    expect(disabled.action).toBe('none');
    expect(disabled.shouldUpdateBracket).toBe(false);

    const inCall = evaluateSpeedVolumeTransition({
      speedKmh: 95,
      currentBracket: 0,
      lastTransitionAtMs: 1000,
      nowMs: 5000,
      isCallActive: true,
      enabled: true,
    });
    expect(inCall.action).toBe('none');
    expect(inCall.shouldUpdateBracket).toBe(false);
  });

  it('enforces 3-second anti-flutter hysteresis before stepping up or down', () => {
    // Under 3 seconds (2500ms): no action
    const tooFast = evaluateSpeedVolumeTransition({
      speedKmh: 60, // Bracket 2
      currentBracket: 0,
      lastTransitionAtMs: 1000,
      nowMs: 3500, // diff = 2500ms
      isCallActive: false,
      enabled: true,
      hysteresisMs: 3000,
    });
    expect(tooFast.action).toBe('none');
    expect(tooFast.shouldUpdateBracket).toBe(false);

    // After 3 seconds (3200ms): triggers step up
    const steppedUp = evaluateSpeedVolumeTransition({
      speedKmh: 60, // Bracket 2
      currentBracket: 0,
      lastTransitionAtMs: 1000,
      nowMs: 4200, // diff = 3200ms
      isCallActive: false,
      enabled: true,
      hysteresisMs: 3000,
    });
    expect(steppedUp.action).toBe('volume_up');
    expect(steppedUp.targetBracket).toBe(2);
    expect(steppedUp.steps).toBe(2);
    expect(steppedUp.shouldUpdateBracket).toBe(true);

    // Slowing down after 3 seconds: triggers step down
    const steppedDown = evaluateSpeedVolumeTransition({
      speedKmh: 10, // Bracket 0
      currentBracket: 2,
      lastTransitionAtMs: 5000,
      nowMs: 8500,
      isCallActive: false,
      enabled: true,
      hysteresisMs: 3000,
    });
    expect(steppedDown.action).toBe('volume_down');
    expect(steppedDown.targetBracket).toBe(0);
    expect(steppedDown.steps).toBe(2);
    expect(steppedDown.shouldUpdateBracket).toBe(true);
  });
});

