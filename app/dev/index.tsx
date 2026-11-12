import React from 'react';
import { View } from 'react-native';
import { useAppState } from '@/state/app';
import { useCompanionState } from '@/state/companion';
import { useDataVersion } from '@/state/dataVersion';
import { checkForAppUpdate, useUpdate } from '@/state/update';
import { Button } from '@/ui/Button';
import { Card } from '@/ui/Card';
import { DetailShell } from '@/ui/shells/DetailShell';
import { NotInRelease } from '@/ui/shells/NotInRelease';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { Text } from '@/ui/Text';

export default function DevDiagnosticsScreen() {
  const { settings } = useAppState();
  const companion = useCompanionState();
  const dataVersion = useDataVersion();
  const update = useUpdate();

  return (
    <NotInRelease>
      <DetailShell title="Dev Diagnostics" eyebrow="INTERNAL ONLY">
        <ScreenScroll>
          <Card className="gap-2">
            <Text className="font-mono text-xs text-primary">RUNTIME SNAPSHOT</Text>
            <View className="gap-1">
              <Text className="font-mono text-xs text-foreground">
                mode: {settings.relayMode} | theme: {settings.theme}
              </Text>
              <Text className="font-mono text-xs text-foreground">
                dataVersion: {dataVersion} | status: {companion.connectionStatus}
              </Text>
              <Text className="font-mono text-xs text-foreground">
                appVersion: {update.currentVersion} | updateCheck: {String(update.enabled)}
              </Text>
            </View>
          </Card>

          <Button
            label="Force Release Check"
            icon="refresh"
            onPress={() => checkForAppUpdate(true)}
          />
        </ScreenScroll>
      </DetailShell>
    </NotInRelease>
  );
}

