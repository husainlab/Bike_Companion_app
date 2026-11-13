# Pillion Engineering Notes

## Why Expo CNG + Local Expo Module
Pillion relies on three Android-only OS capabilities that standard JS packages cannot provide:
1. **Bluetooth RFCOMM SPP** (`00001101-0000-1000-8000-00805F9B34FB`) server & auto-reconnecting client.
2. **`NotificationListenerService`** (`PillionNotificationListener`) reading `MediaSessionManager`, active notifications, battery broadcasts, and incoming call state.
3. **`SYSTEM_ALERT_WINDOW` floating cockpit** (`PillionOverlayService`) drawn over other navigation apps on the handlebar device.

By placing those Kotlin classes in `modules/pillion-native/` and declaring their services/permissions in `plugins/withPillionServices.js`, `npx expo prebuild --platform android --clean` regenerates `android/` reproducibly without ever committing generated Gradle/Android files.

## AMOLED Battery & Sunlight Legibility
- Default theme is pure `#000000` (`DARK_PALETTE`) so OLED panels turn background pixels off during long rides.
- `Atkinson Hyperlegible` differentiates `I/l/1` and `O/0` at high speed in bright daylight; `JetBrains Mono` keeps battery and track timers tabular.
- `global.css` and `src/ui/palette.ts` are verified 1:1 by `src/ui/palette.test.ts` on every Vitest run.
- `scripts/babel-lucide-tree-shake.js` rewrites named `lucide-react-native` imports into single-icon subpath imports (`lucide-react-native/icons/<kebab>`) so Metro does not bundle ~1,900 unused icons.

## Layered Data Flow
- `src/core/`: Pure TypeScript logic, zero I/O.
- `src/data/`: `SqliteStore` (`expo-sqlite` with `withExclusiveTransactionAsync`), `MemoryStore` for fast Node tests, `memoStore` keyed by `dataVersion`, and `companion.ts` native bridge wrapper.
- `src/queries/` & `src/actions/`: Read view models (`QueryCtx`) and `zod`-validated mutations that bump `dataChanged()`.
- `src/screens/useScreen.ts`: Stale-while-revalidate screen cache with `keepEqual` structural sharing and `InteractionManager.runAfterInteractions` cold-render deferral.

## Speed-Sensitive Auto-Volume (Wind Noise Compensation)
- Uses the handlebar phone's native hardware GPS provider (`LocationManager.GPS_PROVIDER`) to sample speed with zero cellular data requirement.
- Maps speed into 4 discrete volume brackets: `0` (< 15 km/h), `1` (15–49 km/h), `2` (50–84 km/h), `3` (>= 85 km/h).
- Employs a 3-second anti-flutter hysteresis timer (`evaluateSpeedVolumeTransition`) so shifting gears or temporary slowing does not cause sudden volume jumping.
- Automatically pauses during active phone calls so conversational volume remains stable.

## Pocket Phone Guardian & Call Safety
- `PillionNotificationListener` captures battery level and temperature via `BatteryManager.EXTRA_TEMPERATURE` and broadcasts `BATTERY|<level>|<tempC>` over Bluetooth SPP.
- `evaluatePocketGuardian` triggers safety alerts for overheating (`>= 45°C`) or critical battery depletion (`<= 8%`).
- `/call/mute` operates strictly on the pocket phone's `AudioManager.isMicrophoneMute`, ensuring call audio stays on the rider's helmet intercom.
- On call termination, `isMicrophoneMute` is automatically reset to `false` so future normal phone calls are never left muted.

## Multi-Message Conversation Thread Extraction & Expandable Alerts
- Standard Android notifications for messaging apps (WhatsApp, Telegram, Signal, Google Messages) bundle conversation history inside `Notification.EXTRA_MESSAGES` (MessagingStyle bundles) or `Notification.EXTRA_TEXT_LINES` (InboxStyle).
- Merely reading `Notification.EXTRA_TEXT` discards earlier unread messages in that conversation, presenting only the single latest text.
- `PillionNotificationListener` inspects `EXTRA_MESSAGES` first, extracting sender name and message body for all incoming texts in the active thread, falling back sequentially to `EXTRA_TEXT_LINES`, `EXTRA_BIG_TEXT`, and `EXTRA_TEXT`.
- Individual messages are encoded over Bluetooth SPP using newline and delimiter escaping (`%0A`, `%7C`, `%25`) to preserve multi-line threads within single-line socket frames.
- Both the floating handlebar overlay (`PillionOverlayService`) and the Alerts screen (`alerts.tsx`) track expansion state per notification, allowing riders to tap `+N MORE` / `COLLAPSE` to expand the full message history without cluttering the screen by default.

## Stability, Process Lifecycle & Deadlock Prevention
- **Reopening After Swipe-from-Recents**: When swiped from Android Recents while the foreground overlay service (`PillionOverlayService`) is running, the Linux process survives but `MainActivity` and its React Native runtime are torn down. On reopen, a new `MainActivity` is created within the existing process.
  - Fail-safe boot watchdog (`bootApp()` timeout of 2,000ms and font watchdog of 1,200ms) prevents `_layout.tsx` from ever being trapped in a `return null` blank screen. Even if SQLite WAL transactions or font loaders delay, the app boots into fallback system typography within 2 seconds.
  - `enableFreeze` is disabled (`false`) to eliminate React 18/19 screen unfreezing deadlocks when returning from background.
- **Throttled Event Bridge & IPC Caching**:
  - High-frequency state transitions (incoming calls, media ticks, volume changes) are throttled to a 100ms debounce posted to `Looper.getMainLooper()`, checking `appContext.hasActiveReactInstance` before dispatching to prevent flooding paused JS runtimes.
  - Android IPC calls (`hasNotificationAccess`, `canDrawOverlays`, `hasRequiredPermissions`) are cached with a 3-second TTL instead of executing 10+ synchronous IPC trips per state tick.
- **Clean Listener Ownership**:
  - `PillionStateStore` maintains a dedicated `moduleListener` slot so each newly created `PillionNativeModule` automatically overwrites and replaces any stale listener lambda referencing destroyed React Contexts.
  - The active Bluetooth `ConnectedThread` is preserved across activity recreation in the module's companion object, preventing socket orphaning.
- **Bidirectional Call Synchronization & Keep-Awake**:
  - `startCallWatchdog` runs continuously in sender mode (including on server boot), polling Android Telecom/Telephony status to immediately broadcast `CALL_END` when a call is hung up on the phone.
  - Both `rootContainer.keepScreenOn = true` and WindowManager `FLAG_KEEP_SCREEN_ON` are actively enforced on the overlay service view hierarchy to guarantee the screen stays awake over Google Maps throughout the ride.
  - View layout boundary clamping auto-adjusts overlay coordinates on post-layout, ensuring call banners and expanded docks never clip off-screen in landscape orientation.


