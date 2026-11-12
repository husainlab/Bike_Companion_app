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

