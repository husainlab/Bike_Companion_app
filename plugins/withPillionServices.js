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
      'android.hardware.location.gps',
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

    // Allow Android 11+ package visibility for launching local Google Maps
    manifest.queries = manifest.queries || [];
    const mapsPackage = 'com.google.android.apps.maps';
    const hasMapsQuery = manifest.queries.some((q) =>
      (q.package || []).some((p) => p.$?.['android:name'] === mapsPackage)
    );
    if (!hasMapsQuery) {
      if (manifest.queries.length > 0) {
        manifest.queries[0].package = manifest.queries[0].package || [];
        manifest.queries[0].package.push({ $: { 'android:name': mapsPackage } });
      } else {
        manifest.queries.push({
          package: [{ $: { 'android:name': mapsPackage } }],
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
        property: [
          {
            $: {
              'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
              'android:value': 'Motorcycle handlebar Bluetooth cockpit floating overlay',
            },
          },
        ],
      });
    }

    return mod;
  });
};

