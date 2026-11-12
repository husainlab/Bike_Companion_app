import { describe, expect, it, vi } from 'vitest';
import { requiredAndroidPermissions } from './permissions';

vi.mock('react-native', () => ({
  Platform: {
    OS: 'android',
    Version: 34,
  },
  PermissionsAndroid: {
    PERMISSIONS: {
      READ_PHONE_STATE: 'android.permission.READ_PHONE_STATE',
      READ_CALL_LOG: 'android.permission.READ_CALL_LOG',
      READ_CONTACTS: 'android.permission.READ_CONTACTS',
      CALL_PHONE: 'android.permission.CALL_PHONE',
      ANSWER_PHONE_CALLS: 'android.permission.ANSWER_PHONE_CALLS',
      BLUETOOTH_CONNECT: 'android.permission.BLUETOOTH_CONNECT',
      BLUETOOTH_SCAN: 'android.permission.BLUETOOTH_SCAN',
      BLUETOOTH_ADVERTISE: 'android.permission.BLUETOOTH_ADVERTISE',
      POST_NOTIFICATIONS: 'android.permission.POST_NOTIFICATIONS',
    },
    RESULTS: {
      GRANTED: 'granted',
      DENIED: 'denied',
    },
    requestMultiple: vi.fn(),
  },
}));

describe('runtime permissions matrix', () => {
  it('requests only Bluetooth and notification permissions in Handlebar (receiver) mode', () => {
    const receiverPerms = requiredAndroidPermissions('receiver');
    expect(receiverPerms).not.toContain('android.permission.READ_PHONE_STATE');
    expect(receiverPerms).not.toContain('android.permission.READ_CALL_LOG');
    expect(receiverPerms).not.toContain('android.permission.READ_CONTACTS');
    expect(receiverPerms).not.toContain('android.permission.CALL_PHONE');
    expect(receiverPerms).not.toContain('android.permission.ANSWER_PHONE_CALLS');
    expect(receiverPerms).toContain('android.permission.BLUETOOTH_CONNECT');
    expect(receiverPerms).toContain('android.permission.POST_NOTIFICATIONS');
  });

  it('requests telephony, call log, contacts and advertise in Pocket Sender mode', () => {
    const senderPerms = requiredAndroidPermissions('sender');
    expect(senderPerms).toContain('android.permission.READ_PHONE_STATE');
    expect(senderPerms).toContain('android.permission.READ_CALL_LOG');
    expect(senderPerms).toContain('android.permission.READ_CONTACTS');
    expect(senderPerms).toContain('android.permission.CALL_PHONE');
    expect(senderPerms).toContain('android.permission.ANSWER_PHONE_CALLS');
    expect(senderPerms).toContain('android.permission.BLUETOOTH_ADVERTISE');
    expect(senderPerms).toContain('android.permission.BLUETOOTH_CONNECT');
  });
});
