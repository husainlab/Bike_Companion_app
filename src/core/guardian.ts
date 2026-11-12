export interface GuardianStatus {
  isLowBattery: boolean;
  isCriticalBattery: boolean;
  isOverheating: boolean;
  isWarm: boolean;
  hasAlert: boolean;
  alertTitle: string;
  alertMessage: string;
  tone: 'critical' | 'warn' | 'normal';
}

/**
 * Pure evaluation of the pocket phone's battery & thermal conditions.
 * Zero I/O, zero React imports, fully deterministic.
 */
export function evaluatePocketGuardian(
  batteryLevel: number,
  batteryTempC: number
): GuardianStatus {
  const validLevel = Number.isFinite(batteryLevel) && batteryLevel >= 0;
  const validTemp = Number.isFinite(batteryTempC) && batteryTempC > 0;

  const isOverheating = validTemp && batteryTempC >= 45;
  const isCriticalBattery = validLevel && batteryLevel <= 8;
  const isLowBattery = validLevel && batteryLevel > 8 && batteryLevel <= 15;
  const isWarm = validTemp && batteryTempC >= 40 && batteryTempC < 45;

  if (isOverheating) {
    return {
      isLowBattery: false,
      isCriticalBattery: false,
      isOverheating: true,
      isWarm: false,
      hasAlert: true,
      alertTitle: `POCKET PHONE OVERHEATING (${batteryTempC}°C)`,
      alertMessage: 'Move phone out of tight bag or direct sun to prevent shutdown.',
      tone: 'critical',
    };
  }

  if (isCriticalBattery) {
    return {
      isLowBattery: true,
      isCriticalBattery: true,
      isOverheating: false,
      isWarm: false,
      hasAlert: true,
      alertTitle: `POCKET BATTERY CRITICAL (${batteryLevel}%)`,
      alertMessage: 'Connect power immediately. Bluetooth link will drop soon.',
      tone: 'critical',
    };
  }

  if (isLowBattery) {
    return {
      isLowBattery: true,
      isCriticalBattery: false,
      isOverheating: false,
      isWarm: false,
      hasAlert: true,
      alertTitle: `POCKET BATTERY LOW (${batteryLevel}%)`,
      alertMessage: 'Charge pocket phone at your next riding stop.',
      tone: 'warn',
    };
  }

  if (isWarm) {
    return {
      isLowBattery: false,
      isCriticalBattery: false,
      isOverheating: false,
      isWarm: true,
      hasAlert: true,
      alertTitle: `POCKET PHONE WARM (${batteryTempC}°C)`,
      alertMessage: 'High ambient heat detected. Monitor pocket airflow.',
      tone: 'warn',
    };
  }

  return {
    isLowBattery: false,
    isCriticalBattery: false,
    isOverheating: false,
    isWarm: false,
    hasAlert: false,
    alertTitle: '',
    alertMessage: '',
    tone: 'normal',
  };
}

