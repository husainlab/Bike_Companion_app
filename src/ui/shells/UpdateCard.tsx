import React from 'react';
import { Linking, View } from 'react-native';
import { formatPercent } from '@/lib/format';
import { isSafeUrl } from '@/lib/url';
import {
  installUpdate,
  putOffUpdate,
  useUpdate,
} from '@/state/update';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

export function UpdateCard() {
  const update = useUpdate();
  const available = update.available;
  if (!available) return null;

  const isDownloading = update.progress !== null && update.progress < 1;
  const isInstalling = update.progress === 1;
  const busy = isDownloading || isInstalling;

  const progressLabel = isInstalling
    ? 'Installing...'
    : isDownloading
      ? `Downloading ${formatPercent(update.progress ?? 0)}`
      : null;

  const handleInstall = async () => {
    try {
      await installUpdate();
    } catch {
      showToast('Allow app installs from Pillion in Android settings', 'error');
    }
  };

  const handleWhatsNew = () => {
    if (isSafeUrl(available.page)) {
      Linking.openURL(available.page).catch(() => {
        showToast('Could not open release notes', 'error');
      });
    }
  };

  return (
    <Card className="border-primary bg-surface">
      <View className="gap-3">
        <View className="flex-row items-center justify-between gap-2">
          <View className="flex-1">
            <Text className="font-mono text-xs uppercase tracking-widest text-primary">
              UPDATE READY
            </Text>
            <Text className="font-bold text-lg text-foreground">
              Pillion v{available.version}
            </Text>
          </View>
          <Text className="font-mono text-xs text-foreground-muted">
            v{update.currentVersion} → v{available.version}
          </Text>
        </View>

        {progressLabel ? (
          <View className="gap-1.5">
            <Text className="font-mono text-sm text-primary">{progressLabel}</Text>
            <View className="h-2 w-full overflow-hidden rounded-xs bg-surface-muted">
              <View
                style={{ width: `${Math.round((update.progress ?? 0) * 100)}%` }}
                className="h-full bg-primary"
              />
            </View>
          </View>
        ) : null}

        {update.checkError ? (
          <Text className="text-sm text-danger">{update.checkError}</Text>
        ) : null}

        <View className="flex-row gap-2">
          <Button
            label={progressLabel ?? 'Update'}
            icon="download"
            tone="primary"
            compact
            disabled={busy}
            onPress={handleInstall}
            className="flex-1"
          />
          <Button
            label="What's new"
            tone="surface"
            compact
            disabled={busy}
            onPress={handleWhatsNew}
          />
          <Button
            label="Later"
            tone="ghost"
            compact
            disabled={busy}
            onPress={putOffUpdate}
          />
        </View>
      </View>
    </Card>
  );
}

