import React from 'react';
import {
  AlertTriangle,
  ArrowLeft,
  Battery,
  BatteryCharging,
  Bell,
  Bluetooth,
  BluetoothConnected,
  BluetoothOff,
  Check,
  ChevronRight,
  Download,
  Layers,
  Pause,
  PhoneIncoming,
  PhoneOff,
  Play,
  Radio,
  RefreshCw,
  Settings,
  ShieldCheck,
  SkipBack,
  SkipForward,
  Smartphone,
  Volume2,
  VolumeX,
} from 'lucide-react-native';
import { useAppState } from '@/state/app';
import { getPalette } from './palette';

export const PILLION_ICONS = {
  alert: AlertTriangle,
  back: ArrowLeft,
  battery: Battery,
  batteryCharging: BatteryCharging,
  bell: Bell,
  bluetooth: Bluetooth,
  bluetoothConnected: BluetoothConnected,
  bluetoothOff: BluetoothOff,
  check: Check,
  chevronRight: ChevronRight,
  download: Download,
  layers: Layers,
  pause: Pause,
  phoneIncoming: PhoneIncoming,
  phoneOff: PhoneOff,
  play: Play,
  radio: Radio,
  refresh: RefreshCw,
  settings: Settings,
  shield: ShieldCheck,
  skipBack: SkipBack,
  skipForward: SkipForward,
  smartphone: Smartphone,
  volume: Volume2,
  volumeMute: VolumeX,
} as const;

export type PillionIconName = keyof typeof PILLION_ICONS;

export interface IconProps {
  name: PillionIconName;
  size?: number;
  color?: string;
  strokeWidth?: number;
}

export function Icon({ name, size = 20, color, strokeWidth = 2.2 }: IconProps) {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const Component = PILLION_ICONS[name];
  return <Component size={size} color={color ?? palette.foreground} strokeWidth={strokeWidth} />;
}

