# Pillion

[![Latest Release](https://img.shields.io/github/v/release/husainlab/Bike_Companion_app?label=Release&color=FF5A1F)](https://github.com/husainlab/Bike_Companion_app/releases)

A two-phone Android riding cockpit. Keep your primary phone in your jacket pocket and mount a spare Android phone on your handlebars to mirror music controls, notifications, incoming calls, and pocket battery over Bluetooth.

## Download

[**Download latest signed APK (`Pillion-release.apk`)**](https://github.com/husainlab/Bike_Companion_app/raw/main/Pillion-release.apk)

All versioned releases and changelogs are published on [GitHub Releases](https://github.com/husainlab/Bike_Companion_app/releases), and installed builds can update directly inside **Setup → Check for updates**.

## How It Works

1. **Pair Both Phones**: Bond your pocket phone and handlebar phone in Android system Bluetooth settings.
2. **Pocket Phone (`Pocket Sender`)**: Switch to **Pocket Sender**, grant Bluetooth/Phone permissions, enable **Notification Access**, and tap **Start Relay Server**.
3. **Handlebar Phone (`Handlebar`)**: Keep on **Handlebar** mode, tap **Connect Paired Phone**, pick your pocket phone, and optionally tap **Launch Floating Overlay** for a draggable cockpit bubble over navigation apps.

## Development

```bash
pnpm install
pnpm typecheck
pnpm lint
pnpm test
npx expo export --platform android --output-dir /tmp/bundle-check
```

See [docs/releases.md](./docs/releases.md) for CI workflows, APK signing fallback, and release publishing details.
