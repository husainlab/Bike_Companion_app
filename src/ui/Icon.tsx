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
  Clock,
  Columns2,
  Delete,
  Download,
  Hash,
  Layers,
  MapPin,
  MessageSquare,
  Navigation,
  Pause,
  Phone,
  PhoneCall,
  PhoneIncoming,
  PhoneMissed,
  PhoneOff,
  PhoneOutgoing,
  Play,
  Radio,
  RefreshCw,
  Search,
  Send,
  Settings,
  ShieldCheck,
  SkipBack,
  SkipForward,
  Smartphone,
  Star,
  Users,
  Volume2,
  VolumeX,
  X,
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
  clock: Clock,
  columns: Columns2,
  delete: Delete,
  download: Download,
  hash: Hash,
  layers: Layers,
  mapPin: MapPin,
  messageSquare: MessageSquare,
  navigation: Navigation,
  pause: Pause,
  phone: Phone,
  phoneCall: PhoneCall,
  phoneIncoming: PhoneIncoming,
  phoneMissed: PhoneMissed,
  phoneOff: PhoneOff,
  phoneOutgoing: PhoneOutgoing,
  play: Play,
  radio: Radio,
  refresh: RefreshCw,
  search: Search,
  send: Send,
  settings: Settings,
  shield: ShieldCheck,
  skipBack: SkipBack,
  skipForward: SkipForward,
  smartphone: Smartphone,
  star: Star,
  users: Users,
  volume: Volume2,
  volumeMute: VolumeX,
  x: X,
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

