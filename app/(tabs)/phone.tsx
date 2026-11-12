import React, { useMemo, useState } from 'react';
import { TextInput, View } from 'react-native';
import {
  filterContacts,
  formatCallTypeLabel,
  type ContactItem,
} from '@/core/cockpit';
import {
  dialContactNumber,
  syncCompanionContacts,
} from '@/data/companion';
import { formatRelativeTime } from '@/lib/time';
import { useAppState } from '@/state/app';
import { useCompanionState } from '@/state/companion';
import { Button } from '@/ui/Button';
import { CallHudCard } from '@/ui/CallHudCard';
import { Card } from '@/ui/Card';
import { Icon, type PillionIconName } from '@/ui/Icon';
import { getPalette } from '@/ui/palette';
import { Press } from '@/ui/Press';
import { Segmented } from '@/ui/Segmented';
import { PageShell } from '@/ui/shells/PageShell';
import { ScreenScroll } from '@/ui/shells/ScreenScroll';
import { Text } from '@/ui/Text';
import { showToast } from '@/ui/Toast';

type PhoneSubTab = 'contacts' | 'recents' | 'keypad';

const KEYPAD_ROWS = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['*', '0', '#'],
] as const;

function callTypeIcon(callType: number): PillionIconName {
  if (callType === 1) return 'phoneIncoming';
  if (callType === 2) return 'phoneOutgoing';
  if (callType === 3) return 'phoneMissed';
  return 'phone';
}

export default function PhoneScreen() {
  const { settings } = useAppState();
  const palette = getPalette(settings.theme);
  const companion = useCompanionState();

  const [tab, setTab] = useState<PhoneSubTab>('contacts');
  const [searchQuery, setSearchQuery] = useState('');
  const [dialInput, setDialInput] = useState('');
  const [syncing, setSyncing] = useState(false);

  const filteredContacts = useMemo(
    () => filterContacts<ContactItem>(companion.contacts, searchQuery),
    [companion.contacts, searchQuery]
  );

  const handleDial = async (number: string, displayLabel?: string) => {
    const cleaned = number.trim();
    if (!cleaned) {
      showToast('Enter or pick a phone number first', 'info');
      return;
    }
    if (companion.connectionStatus !== 'Connected' && settings.relayMode === 'receiver') {
      showToast('Connect to Pocket Phone over Bluetooth first', 'info');
      return;
    }
    await dialContactNumber(cleaned);
    showToast(`Dialing ${displayLabel || cleaned} on Pocket Phone`, 'success');
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      await syncCompanionContacts();
      showToast('Requested contacts & recents sync', 'success');
    } finally {
      setSyncing(false);
    }
  };

  return (
    <PageShell
      title="Phone & Contacts"
      eyebrow="POCKET AUDIO RELAY"
      rightSlot={
        <Press
          onPress={handleSync}
          disabled={syncing}
          accessibilityLabel="Sync contacts"
          className="flex-row items-center gap-1.5 rounded-xs border-[1.5px] border-border bg-surface px-2.5 py-1.5"
        >
          <Icon name="refresh" size={14} color={palette.primary} />
          <Text className="font-mono-bold text-xs text-foreground">
            {syncing ? 'SYNC...' : `${companion.contacts.length}`}
          </Text>
        </Press>
      }
    >
      <ScreenScroll>
        <CallHudCard companion={companion} />

        <Card tone="muted" className="flex-row items-center justify-between gap-3 py-3">
          <View className="flex-1 gap-0.5">
            <Text className="font-mono-bold text-xs text-primary">
              CONTROLS ONLY • ZERO AUDIO ROUTING
            </Text>
            <Text className="text-xs text-foreground-muted">
              Calls dial & route audio strictly through your pocket phone or helmet intercom.
            </Text>
          </View>
        </Card>

        <Segmented
          options={[
            { value: 'contacts', label: 'Contacts' },
            { value: 'recents', label: 'Recents' },
            { value: 'keypad', label: 'Keypad' },
          ]}
          value={tab}
          onChange={setTab}
        />

        {tab === 'contacts' ? (
          <View className="gap-3">
            <View className="flex-row items-center gap-2 rounded-sm border-[1.5px] border-border bg-surface px-3.5 py-2.5">
              <Icon name="search" size={18} color={palette.foregroundMuted} />
              <TextInput
                value={searchQuery}
                onChangeText={setSearchQuery}
                placeholder="Search contacts or number..."
                placeholderTextColor={palette.foregroundMuted}
                style={{ color: palette.foreground }}
                className="flex-1 font-sans text-base"
                autoCorrect={false}
              />
              {searchQuery.length > 0 ? (
                <Press
                  onPress={() => setSearchQuery('')}
                  accessibilityLabel="Clear search"
                  className="p-1"
                >
                  <Icon name="x" size={16} color={palette.foregroundMuted} />
                </Press>
              ) : null}
            </View>

            {filteredContacts.length === 0 ? (
              <Card tone="muted" className="gap-3">
                <Icon name="users" size={22} color={palette.foregroundMuted} />
                <Text className="font-bold text-base text-foreground">
                  {companion.contacts.length === 0
                    ? 'No contacts synced yet'
                    : 'No matching contacts'}
                </Text>
                <Text className="text-sm text-foreground-muted">
                  Grant Contacts permission on Pocket Phone and connect over Bluetooth, or tap Sync Contacts.
                </Text>
                <Button
                  label="Sync Contacts Now"
                  icon="refresh"
                  tone="primary"
                  compact
                  onPress={handleSync}
                />
              </Card>
            ) : (
              filteredContacts.map((contact) => (
                <Press
                  key={contact.id}
                  onPress={() => handleDial(contact.phoneNumber, contact.name)}
                  haptic="confirm"
                  className="flex-row items-center justify-between gap-3 rounded-md border-[1.5px] border-border bg-surface px-4 py-3.5"
                >
                  <View className="flex-1 min-w-0 gap-0.5">
                    <View className="flex-row items-center gap-1.5">
                      {contact.isStarred ? (
                        <Icon name="star" size={14} color={palette.primary} />
                      ) : null}
                      <Text className="font-bold text-base text-foreground flex-1 min-w-0 break-words" numberOfLines={1}>
                        {contact.name}
                      </Text>
                    </View>
                    <Text className="font-mono text-xs text-foreground-muted break-words" numberOfLines={1}>
                      {contact.phoneNumber}
                    </Text>
                  </View>
                  <View className="h-11 w-11 shrink-0 items-center justify-center rounded-sm border-[1.5px] border-accent bg-accent">
                    <Icon name="phoneCall" size={20} color={palette.onPrimary} />
                  </View>
                </Press>
              ))
            )}
          </View>
        ) : null}

        {tab === 'recents' ? (
          <View className="gap-3">
            {companion.recentCalls.length === 0 ? (
              <Card tone="muted" className="gap-3">
                <Icon name="clock" size={22} color={palette.foregroundMuted} />
                <Text className="font-bold text-base text-foreground">
                  No recent calls synced
                </Text>
                <Text className="text-sm text-foreground-muted">
                  Call Log permission on the pocket phone streams your latest 25 calls automatically on connect.
                </Text>
                <Button
                  label="Refresh Recents"
                  icon="refresh"
                  tone="surface"
                  compact
                  onPress={handleSync}
                />
              </Card>
            ) : (
              companion.recentCalls.map((entry) => {
                const isMissed = entry.callType === 3;
                return (
                  <Press
                    key={entry.id}
                    onPress={() => handleDial(entry.phoneNumber, entry.name)}
                    haptic="confirm"
                    className="flex-row items-center justify-between gap-3 rounded-md border-[1.5px] border-border bg-surface px-4 py-3.5"
                  >
                    <Icon
                      name={callTypeIcon(entry.callType)}
                      size={20}
                      color={isMissed ? palette.danger : palette.accent}
                    />
                    <View className="flex-1 min-w-0 gap-0.5">
                      <Text className="font-bold text-base text-foreground break-words" numberOfLines={1}>
                        {entry.name || entry.phoneNumber}
                      </Text>
                      <Text className="font-mono text-xs text-foreground-muted break-words" numberOfLines={1}>
                        {formatCallTypeLabel(entry.callType)} • {entry.phoneNumber} •{' '}
                        {entry.timestampMs > 0 ? formatRelativeTime(entry.timestampMs) : 'recent'}
                      </Text>
                    </View>
                    <View className="h-11 w-11 shrink-0 items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted">
                      <Icon name="phoneCall" size={18} color={palette.accent} />
                    </View>
                  </Press>
                );
              })
            )}
          </View>
        ) : null}

        {tab === 'keypad' ? (
          <Card className="gap-4">
            <View className="flex-row items-center justify-between gap-2 rounded-sm border-[1.5px] border-border bg-surface-muted px-4 py-3">
              <Text className="font-mono-bold text-2xl text-foreground flex-1" numberOfLines={1}>
                {dialInput || 'Enter number'}
              </Text>
              <Press
                onPress={() => setDialInput((prev) => prev.slice(0, -1))}
                onLongPress={() => setDialInput('')}
                accessibilityLabel="Backspace"
                className="p-2"
              >
                <Icon name="delete" size={22} color={palette.foreground} />
              </Press>
            </View>

            <View className="gap-2.5">
              {KEYPAD_ROWS.map((row) => (
                <View key={row.join('')} className="flex-row gap-2.5">
                  {row.map((digit) => (
                    <Press
                      key={digit}
                      onPress={() => setDialInput((prev) => `${prev}${digit}`)}
                      onLongPress={() => {
                        if (digit === '0') {
                          setDialInput((prev) => `${prev}+`);
                        }
                      }}
                      className="flex-1 min-h-hit items-center justify-center rounded-sm border-[1.5px] border-border bg-surface-muted"
                    >
                      <Text className="font-mono-bold text-2xl text-foreground">{digit}</Text>
                    </Press>
                  ))}
                </View>
              ))}
            </View>

            <Button
              label="Call via Pocket Phone"
              icon="phoneCall"
              tone="accent"
              onPress={() => handleDial(dialInput)}
            />
          </Card>
        ) : null}
      </ScreenScroll>
    </PageShell>
  );
}
