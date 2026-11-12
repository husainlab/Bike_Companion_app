import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const ROOT = process.cwd();

describe('application architecture & regression contracts', () => {
  it('keeps sqlite & native module boundaries strict outside src/data', () => {
    const restrictedDirs = ['src/core', 'src/actions', 'src/queries', 'app'];
    for (const relDir of restrictedDirs) {
      const fullDir = path.join(ROOT, relDir);
      const stack = [fullDir];
      while (stack.length > 0) {
        const current = stack.pop()!;
        for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
          const target = path.join(current, entry.name);
          if (entry.isDirectory()) {
            stack.push(target);
          } else if (/\.(ts|tsx)$/.test(entry.name) && !entry.name.includes('.test.')) {
            const src = fs.readFileSync(target, 'utf8');
            expect(src, `${target} must not import expo-sqlite directly`).not.toMatch(/from ['"]expo-sqlite['"]/);
          }
        }
      }
    }
  });

  it('keeps manual update error checks reading latest store state and role switch tearing down previous mode', () => {
    const settingsSrc = fs.readFileSync(path.join(ROOT, 'app/(tabs)/settings.tsx'), 'utf8');
    expect(settingsSrc).toContain('getUpdate()');
    expect(settingsSrc).not.toContain('else if (update.checkError)');

    const cockpitSrc = fs.readFileSync(path.join(ROOT, 'app/(tabs)/index.tsx'), 'utf8');
    expect(cockpitSrc).toContain('teardownPreviousRelayMode');
  });

  it('keeps every required Expo route file present', () => {
    const routes = [
      'app/_layout.tsx',
      'app/onboarding.tsx',
      'app/connect.tsx',
      'app/receiver.tsx',
      'app/sender.tsx',
      'app/(tabs)/_layout.tsx',
      'app/(tabs)/index.tsx',
      'app/(tabs)/phone.tsx',
      'app/(tabs)/alerts.tsx',
      'app/(tabs)/settings.tsx',
    ];
    for (const rel of routes) {
      expect(fs.existsSync(path.join(ROOT, rel)), `Missing route: ${rel}`).toBe(true);
    }
  });

  it('keeps Android overlay service foregrounded cleanly in native source & manifest plugin', () => {
    const overlayKt = fs.readFileSync(
      path.join(
        ROOT,
        'modules/pillion-native/android/src/main/java/com/husainlab/pillion/nativecore/PillionOverlayService.kt'
      ),
      'utf8'
    );
    expect(overlayKt).toContain('startForeground(');
    expect(overlayKt).toContain('Settings.canDrawOverlays');

    const nativeModuleKt = fs.readFileSync(
      path.join(
        ROOT,
        'modules/pillion-native/android/src/main/java/com/husainlab/pillion/nativecore/PillionNativeModule.kt'
      ),
      'utf8'
    );
    expect(nativeModuleKt).toContain('startForegroundService(');
    expect(nativeModuleKt).toContain('LinkedBlockingQueue');
    expect(nativeModuleKt).toContain('resetTransientState');
    expect(nativeModuleKt).toContain('PREF_AUTO_START_OVERLAY');
    expect(nativeModuleKt).toContain('/call/mute');
    expect(nativeModuleKt).toContain('CALL_MUTE|');

    const overlayServiceKt = fs.readFileSync(
      path.join(
        ROOT,
        'modules/pillion-native/android/src/main/java/com/husainlab/pillion/nativecore/PillionOverlayService.kt'
      ),
      'utf8'
    );
    expect(overlayServiceKt).toContain('formatCallElapsed');
    expect(overlayServiceKt).toContain('/call/mute');

    const protocolKt = fs.readFileSync(
      path.join(
        ROOT,
        'modules/pillion-native/android/src/main/java/com/husainlab/pillion/nativecore/PillionProtocol.kt'
      ),
      'utf8'
    );
    expect(protocolKt).toContain('data class CallMute');
    expect(protocolKt).toContain('fun formatCallElapsed(');

    const cockpitSrc = fs.readFileSync(path.join(ROOT, 'app/(tabs)/index.tsx'), 'utf8');
    expect(cockpitSrc).toContain('<CallHudCard');
    expect(cockpitSrc).toContain('<PocketGuardianCard');

    const phoneSrc = fs.readFileSync(path.join(ROOT, 'app/(tabs)/phone.tsx'), 'utf8');
    expect(phoneSrc).toContain('<CallHudCard');

    const settingsSrc = fs.readFileSync(path.join(ROOT, 'app/(tabs)/settings.tsx'), 'utf8');
    expect(settingsSrc).toContain('autoVolumeEnabled');
  });
});

