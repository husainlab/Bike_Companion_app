const fs = require('fs');
const path = require('path');
const { withAndroidManifest, withDangerousMod } = require('expo/config-plugins');

/**
 * Generates Android launcher shortcuts (`pillion://receiver`, `pillion://sender`)
 * and binds `android.app.shortcuts` metadata on the launcher activity.
 */
module.exports = function withAppShortcuts(config) {
  config = withAndroidManifest(config, (mod) => {
    const app = mod.modResults.manifest.application?.[0];
    if (!app) return mod;

    const mainActivity = app.activity?.find((act) =>
      act['intent-filter']?.some((filter) =>
        filter.action?.some((a) => a.$['android:name'] === 'android.intent.action.MAIN')
      )
    );

    if (mainActivity) {
      mainActivity['meta-data'] = mainActivity['meta-data'] || [];
      const exists = mainActivity['meta-data'].some(
        (m) => m.$['android:name'] === 'android.app.shortcuts'
      );
      if (!exists) {
        mainActivity['meta-data'].push({
          $: {
            'android:name': 'android.app.shortcuts',
            'android:resource': '@xml/shortcuts',
          },
        });
      }
    }
    return mod;
  });

  return withDangerousMod(config, [
    'android',
    async (mod) => {
      const resDir = path.join(mod.modRequest.platformProjectRoot, 'app/src/main/res');
      const xmlDir = path.join(resDir, 'xml');
      const valuesDir = path.join(resDir, 'values');
      const drawableDir = path.join(resDir, 'drawable');

      fs.mkdirSync(xmlDir, { recursive: true });
      fs.mkdirSync(valuesDir, { recursive: true });
      fs.mkdirSync(drawableDir, { recursive: true });

      fs.writeFileSync(
        path.join(valuesDir, 'strings_shortcuts.xml'),
        `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="shortcut_receiver_short">Cockpit</string>
    <string name="shortcut_receiver_long">Open Handlebar Cockpit</string>
    <string name="shortcut_sender_short">Pocket Sender</string>
    <string name="shortcut_sender_long">Start Pocket Relay</string>
</resources>
`
      );

      fs.writeFileSync(
        path.join(drawableDir, 'shortcut_receiver.xml'),
        `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF5A1F"
        android:pathData="M4,6h16v10H4V6zm2,2v6h12V8H6zm-1,10h14v2H5v-2z" />
</vector>
`
      );

      fs.writeFileSync(
        path.join(drawableDir, 'shortcut_sender.xml'),
        `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FF5A1F"
        android:pathData="M12,4l4,4h-3v7h-2V8H8l4-4zm-7,13h14v2H5v-2z" />
</vector>
`
      );

      fs.writeFileSync(
        path.join(xmlDir, 'shortcuts.xml'),
        `<?xml version="1.0" encoding="utf-8"?>
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <shortcut
        android:shortcutId="open_receiver"
        android:enabled="true"
        android:icon="@drawable/shortcut_receiver"
        android:shortcutShortLabel="@string/shortcut_receiver_short"
        android:shortcutLongLabel="@string/shortcut_receiver_long">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.husainlab.pillion"
            android:targetClass="com.husainlab.pillion.MainActivity"
            android:data="pillion://receiver" />
    </shortcut>
    <shortcut
        android:shortcutId="open_sender"
        android:enabled="true"
        android:icon="@drawable/shortcut_sender"
        android:shortcutShortLabel="@string/shortcut_sender_short"
        android:shortcutLongLabel="@string/shortcut_sender_long">
        <intent
            android:action="android.intent.action.VIEW"
            android:targetPackage="com.husainlab.pillion"
            android:targetClass="com.husainlab.pillion.MainActivity"
            android:data="pillion://sender" />
    </shortcut>
</shortcuts>
`
      );

      return mod;
    },
  ]);
};

