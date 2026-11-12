import { beforeEach, describe, expect, it, vi } from 'vitest';
import { applyTheme, bootApp, resetAppStateForTests } from './app';
import { DEFAULT_APP_SETTINGS } from '@/core/types';

const mocks = vi.hoisted(() => ({
  initCalls: 0,
  shouldFailOnce: false,
  setColorScheme: vi.fn(),
}));

vi.mock('nativewind', () => ({
  colorScheme: {
    set: mocks.setColorScheme,
  },
}));

vi.mock('@/data/db', () => ({
  SqliteStore: class {
    async init() {
      mocks.initCalls += 1;
      if (mocks.shouldFailOnce) {
        mocks.shouldFailOnce = false;
        throw new Error('SQLite busy');
      }
    }
    async getSettings() {
      return { ...DEFAULT_APP_SETTINGS, onboardingComplete: true };
    }
    async updateSettings(patch: Record<string, unknown>) {
      return { ...DEFAULT_APP_SETTINGS, ...patch };
    }
    async listPairedDevices() {
      return [];
    }
    async upsertPairedDevice() {}
    async listRideSessions() {
      return [];
    }
    async saveRideSession() {}
    async clearRideSessions() {}
  },
}));

describe('bootApp lifecycle', () => {
  beforeEach(() => {
    mocks.initCalls = 0;
    mocks.shouldFailOnce = false;
    mocks.setColorScheme.mockClear();
    resetAppStateForTests();
  });

  it('recovers from an initial boot failure instead of poisoning future boots', async () => {
    mocks.shouldFailOnce = true;
    await expect(bootApp()).rejects.toThrow('SQLite busy');

    const recovered = await bootApp();
    expect(recovered.settings.onboardingComplete).toBe(true);
    expect(mocks.initCalls).toBe(2);
  });

  it('applies theme scheme changes safely', () => {
    applyTheme('light');
    applyTheme('dark');
    expect(mocks.setColorScheme).toHaveBeenNthCalledWith(1, 'light');
    expect(mocks.setColorScheme).toHaveBeenNthCalledWith(2, 'dark');
  });
});
