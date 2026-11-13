# Pillion

[![Latest Release](https://img.shields.io/github/v/release/husainlab/Bike_Companion_app?label=Release&color=FF5A1F)](https://github.com/husainlab/Bike_Companion_app/releases)

A two-phone Android riding cockpit designed for motorcyclists. Keep your primary phone safe in your jacket pocket and mount a spare Android phone on your handlebars to mirror media transport, incoming calls, notifications, and telemetry over offline Bluetooth SPP.

## Key Features

- **Controls-Only Architecture**: Zero audio or call routing through the handlebar phone. Your helmet intercom (Cardo, Sena) stays paired directly to your pocket phone for maximum audio quality and zero lag.
- **Speed-Sensitive Auto-Volume & Volume HUD**: Automatically compensates for motorcycle wind noise using offline hardware GPS speed, featuring a 3-second anti-flutter hysteresis filter and active call suppression. A high-visibility temporary volume HUD pops up on any volume button press or auto-volume transition and auto-dismisses after 2.5s.
- **Seekable Media Progress Bar**: Scrub and seek forwards and backwards through tracks directly on the cockpit deck or the floating overlay widget.
- **Actionable Badges & Clean Layout**: Distraction-free interface where badge counts only appear when actionable items exist (missed calls or active unread notifications), with robust multi-line text wrapping for glove touch targets.
- **Multi-Message Thread Extraction & Expandable Alerts**: Full conversation history support for WhatsApp, SMS, Google Messages, Signal, and Telegram. When a sender sends multiple texts, the entire conversation thread is captured via Android `MessagingStyle`/`InboxStyle` extras and transmitted over Bluetooth SPP. The floating cockpit widget and alerts screen feature glove-friendly `+N MORE` / `COLLAPSE` toggles so you can expand and read the full text sequence on the road.
- **Pocket Phone Guardian**: Real-time battery level and thermal watchdog beaming battery temperature and percentage over Bluetooth, with instant high-visibility alerts if your pocket phone overheats or drops below 15%.
- **Bidirectional Call Management HUD & Screen Wake**: Answer, decline, or one-touch **Mute** calls directly from your handlebars or floating overlay, backed by a 3-layer watchdog ensuring seamless bidirectional sync when ending calls on the pocket phone dialer. WindowManager keep-awake and wake locks keep the display awake over Google Maps throughout your ride.
- **Auto-Start Floating Overlay**: Draggable cockpit overlay bubble launches automatically over Google Maps whenever the handlebar phone connects to your pocket phone.
- **Sunlight & Glove Optimized**: Deep AMOLED `#000000` dark mode, 64dp+ oversized glove-friendly touch targets, and adaptive text wrapping for tracks, callers, and notifications.

## Download

[**Download latest signed APK (`Pillion-release.apk`)**](https://github.com/husainlab/Bike_Companion_app/raw/main/Pillion-release.apk)

All versioned releases and changelogs are published on [GitHub Releases](https://github.com/husainlab/Bike_Companion_app/releases), and installed builds can update directly inside **Setup → Check for updates**.

## How It Works

1. **Pair Both Phones**: Bond your pocket phone and handlebar phone in Android system Bluetooth settings.
2. **Pocket Phone (`Pocket Sender`)**: Switch to **Pocket Sender**, grant Bluetooth/Phone permissions, enable **Notification Access**, and tap **Start Relay Server**.
3. **Handlebar Phone (`Handlebar`)**: Keep on **Handlebar** mode, tap **Connect Paired Phone**, pick your pocket phone, and optionally enable **Auto-start overlay on connect** for an automatic cockpit bubble over navigation apps.

## Development

```bash
pnpm install
pnpm typecheck
pnpm lint
pnpm test
npx expo export --platform android --output-dir /tmp/bundle-check
```

See [docs/releases.md](./docs/releases.md) for CI workflows, APK signing fallback, and release publishing details.
