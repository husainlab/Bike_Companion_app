import { PermissionsAndroid, Platform, type Permission } from 'react-native';

export function requiredAndroidPermissions(): Permission[] {
  if (Platform.OS !== 'android') return [];
  const api = typeof Platform.Version === 'number' ? Platform.Version : Number(Platform.Version);
  const list: Permission[] = [
    PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
    PermissionsAndroid.PERMISSIONS.READ_CALL_LOG,
    PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
  ];
  if (api >= 26 && PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS) {
    list.push(PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS);
  }
  if (api >= 31) {
    if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT);
    }
    if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN);
    }
    if (PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE) {
      list.push(PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE);
    }
  }
  if (api >= 33 && PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS) {
    list.push(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
  }
  return list;
}

export async function requestAllPillionPermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  const perms = requiredAndroidPermissions();
  const results = await PermissionsAndroid.requestMultiple(perms);
  return perms.every((p) => results[p] === PermissionsAndroid.RESULTS.GRANTED);
}

