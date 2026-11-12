const { withAndroidManifest } = require('expo/config-plugins');

/**
 * Ensures AndroidManifest declares optional hardware features and registers
 * Pillion's native NotificationListenerService and OverlayService.
 */
module.exports = function withPillionServices(config) {
  return withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;

    manifest['uses-feature'] = manifest['uses-feature'] || [];
    const requiredFeatures = [
      'android.hardware.telephony',
      'android.hardware.bluetooth',
    ];
    for (const feature of requiredFeatures) {
      const exists = manifest['uses-feature'].some(
        (f) => f.$['android:name'] === feature
      );
      if (!exists) {
        manifest['uses-feature'].push({
          $: {
            'android:name': feature,
            'android:required': 'false',
          },
        });
      }
    }

    const app = manifest.application?.[0];
    if (!app) return mod;
    app.service = app.service || [];

    const listenerName = 'com.husainlab.pillion.nativecore.PillionNotificationListener';
    if (!app.service.some((s) => s.$['android:name'] === listenerName)) {
      app.service.push({
        $: {
          'android:name': listenerName,
          'android:label': 'Pillion Notification Listener',
          'android:permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
          'android:exported': 'true',
        },
        'intent-filter': [
          {
            action: [
              {
                $: {
                  'android:name': 'android.service.notification.NotificationListenerService',
                },
              },
            ],
          },
        ],
      });
    }

    const overlayName = 'com.husainlab.pillion.nativecore.PillionOverlayService';
    if (!app.service.some((s) => s.$['android:name'] === overlayName)) {
      app.service.push({
        $: {
          'android:name': overlayName,
          'android:exported': 'false',
          'android:foregroundServiceType': 'specialUse',
        },
      });
    }

    return mod;
  });
};

