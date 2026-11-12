import { describe, expect, it } from 'vitest';
import { evaluatePocketGuardian } from './guardian';

describe('evaluatePocketGuardian', () => {
  it('returns normal status when battery is healthy and temperature is nominal', () => {
    const status = evaluatePocketGuardian(75, 34);
    expect(status.hasAlert).toBe(false);
    expect(status.tone).toBe('normal');
    expect(status.isOverheating).toBe(false);
    expect(status.isLowBattery).toBe(false);
  });

  it('triggers critical overheating alert when temperature hits 45C or higher', () => {
    const status = evaluatePocketGuardian(60, 46);
    expect(status.hasAlert).toBe(true);
    expect(status.tone).toBe('critical');
    expect(status.isOverheating).toBe(true);
    expect(status.alertTitle).toContain('46°C');
  });

  it('triggers warm alert between 40C and 44C', () => {
    const status = evaluatePocketGuardian(80, 42);
    expect(status.hasAlert).toBe(true);
    expect(status.tone).toBe('warn');
    expect(status.isWarm).toBe(true);
    expect(status.alertTitle).toContain('42°C');
  });

  it('triggers critical battery alert at or below 8%', () => {
    const status = evaluatePocketGuardian(6, 32);
    expect(status.hasAlert).toBe(true);
    expect(status.tone).toBe('critical');
    expect(status.isCriticalBattery).toBe(true);
    expect(status.alertTitle).toContain('6%');
  });

  it('triggers low battery warning between 9% and 15%', () => {
    const status = evaluatePocketGuardian(12, 30);
    expect(status.hasAlert).toBe(true);
    expect(status.tone).toBe('warn');
    expect(status.isLowBattery).toBe(true);
    expect(status.alertTitle).toContain('12%');
  });

  it('handles negative or invalid values gracefully without throwing', () => {
    const status = evaluatePocketGuardian(-1, -1);
    expect(status.hasAlert).toBe(false);
    expect(status.tone).toBe('normal');
  });
});

