import { PermissionsAndroid, Platform, type Permission } from 'react-native';
import type { RelayMode } from '@/core/types';

export function requiredAndroidPermissions(mode: RelayMode = 'sender'): Permission[] {
  if (Platform.OS !== 'android') return [];
  const api = typeof Platform.Version === 'number' ? Platform.Version : Number(Platform.Version);
  const list: Permission[] = [];

  if (mode === 'sender') {
    list.push(
      PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
      PermissionsAndroid.PERMISSIONS.READ_CALL_LOG,
      PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
      PermissionsAndroid.PERMISSIONS.CALL_PHONE
    );
    if (api >= 26 && PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS) {
      list.push(PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS);
    }
    if (api >= 31 && PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE);
    }
  }

  if (api >= 31) {
    if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
    }
    if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN);
    }
  }
  if (api >= 33 && PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS) {
    list.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }
  return list;
}

export async function requestAllPillionPermissions(
  mode: RelayMode = 'sender'
): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  const perms = requiredAndroidPermissions(mode);
  if (perms.length === 0) return true;
  const results = await PermissionsAndroid.requestMultiple(perms);
  return perms.every((p) => results[p] === PermissionsAndroid.RESULTS.GRANTED);
}

