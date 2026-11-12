import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { recordConnectedDevice, startRideSession } from '@/actions/cockpit';
import {
  connectToSenderDevice,
  getPairedBluetoothDevices,
  startCockpitOverlay,
  type PairedDevice,
} from '@/data/companion';
import { shortAddress } from '@/lib/format';
import { loadCockpitOverview } from '@/queries/cockpit';
import { useScreen } from '@/screens/useScreen';
import { getStore, useAppState } from '@/state/app';
import { refreshCompanionSnapshot } from '@/state/companion';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Icon } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { TaskPage } from '@/ui/shells/TaskPage';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

export default function ConnectDeviceScreen() {
  const router = useRouter();
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const [devices, setDevices] = useState<PairedDevice[]>([]);
  const [loading, setLoading] = useState(true);
  const [connectingAddr, setConnectingAddr] = useState<string | null>(null);
  const { data: overview } = useScreen('connect-overview', [], loadCockpitOverview);

  const loadDevices = async () => {
    setLoading(true);
    try {
      const list = await getPairedBluetoothDevices();
      setDevices(list);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;
    getPairedBluetoothDevices()
      .then((list) => {
        if (!cancelled) setDevices(list);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSelect = async (device: PairedDevice) => {
    setConnectingAddr(device.address);
    try {
      await connectToSenderDevice(device.address);
      await recordConnectedDevice(getStore(), {
        address: device.address,
        name: device.name,
      });
      await startRideSession(getStore(), 'receiver', device.name);
      if (settings.autoStartOverlay) {
        await startCockpitOverlay().catch(() => {});
      }
      await refreshCompanionSnapshot();
      showToast(`Connecting to ${device.name}`, 'info');
      router.back();
    } catch {
      showToast(`Could not connect to ${device.name}`, 'error');
    } finally {
      setConnectingAddr(null);
    }
  };

  return (
    <TaskPage
      title="Paired Phones"
      eyebrow="BLUETOOTH SPP"
      footer={
        <Button
          label="Refresh Paired List"
          icon="refresh"
          tone="surface"
          loading={loading}
          onPress={loadDevices}
        />
      }
    >
      <Text className="text-sm text-foreground-muted">
        Select the pocket phone running Pillion in Pocket Sender mode. Both phones must already be bonded in Android Bluetooth settings.
      </Text>

      {devices.length === 0 && !loading ? (
        <Card tone="muted" className="gap-2">
          <Icon name="bluetoothOff" size={22} color={palette.warning} />
          <Text className="font-bold text-base text-foreground">No bonded devices found</Text>
          <Text className="text-sm text-foreground-muted">
            Pair your two Android phones in Android system Bluetooth settings first, then tap Refresh.
          </Text>
        </Card>
      ) : null}

      <View className="gap-2.5">
        {devices.map((device) => {
          const isBusy = connectingAddr === device.address;
          const wasRecent = overview?.recentDevices.some((d) => d.address === device.address);
          return (
            <Press
              key={device.address}
              onPress={() => handleSelect(device)}
              disabled={connectingAddr !== null}
              className="flex-row items-center justify-between rounded-md border-[1.5px] border-border bg-surface p-4 min-h-hit"
            >
              <View className="flex-row items-center gap-3 flex-1">
                <Icon
                  name="bluetooth"
                  size={20}
                  color={wasRecent ? palette.accent : palette.primary}
                />
                <View className="flex-1">
                  <Text className="font-bold text-base text-foreground" numberOfLines={1}>
                    {device.name}
                  </Text>
                  <Text className="font-mono text-xs text-foreground-muted">
                    {shortAddress(device.address)}
                    {wasRecent ? ' • RECENT' : ''}
                  </Text>
                </View>
              </View>
              <Text className="font-mono-bold text-xs text-primary">
                {isBusy ? 'LINKING...' : 'CONNECT'}
              </Text>
            </Press>
          );
        })}
      </View>
    </TaskPage>
  );
}
