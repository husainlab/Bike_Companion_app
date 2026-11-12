# Pillion Agent & Developer Guide

## Verification Commands
Run all four checks before committing or opening a PR:
```bash
pnpm typecheck
pnpm lint
pnpm test
npx expo export --platform android --output-dir /tmp/bundle-check && rm -rf /tmp/bundle-check
```

## Architecture & Boundary Rules
1. **Continuous Native Generation (CNG)**: Never hand-edit or commit `android/` or `ios/`. All native configuration lives in `app.json`, `plugins/`, and `modules/pillion-native/`.
2. **SDK-matched dependencies**: Always install Expo and React Native packages with `npx expo install <pkg>` so versions remain aligned with Expo SDK 58. Never let Dependabot bump npm packages automatically.
3. **Pure domain core (`src/core/`)**: Zero I/O, zero `Date.now()`, zero SQLite, zero `fetch`, zero React imports. Every helper in `src/core/` must have deterministic unit tests beside it.
4. **Persistence boundary (`src/data/`)**: Only `src/data/` touches `expo-sqlite` or `modules/pillion-native`. Screens read via `src/queries/` + `useScreen()` and mutate via `src/actions/` (validated with `zod`, calling `dataChanged()`).
5. **Routes (`app/`)**: Keep Expo Router route files thin—compose `src/ui/` shells, `src/queries/`, `src/actions/`, and `src/state/` without embedding business logic.
6. **Design system**: Default to AMOLED `#000000` (`dark`) with `Atkinson Hyperlegible` + `JetBrains Mono`. Keep `global.css` and `src/ui/palette.ts` in 1:1 parity (enforced by `src/ui/palette.test.ts`).

## Git Conventions
- Use Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`).
- Sign off commits (`git commit -s`).
- Never push commits to `main` that omit `[skip ci]` from automated release commits.

