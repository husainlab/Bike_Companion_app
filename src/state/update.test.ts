import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  checkForAppUpdate,
  getUpdate,
  installUpdate,
  putOffUpdate,
  resetUpdateStateForTests,
  setUpdateCheck,
} from './update';

const mocks = vi.hoisted(() => ({
  storage: new Map<string, string>(),
  mockDelete: vi.fn(),
  mockCreateDownloadTask: vi.fn(),
  mockStartActivityAsync: vi.fn(),
  mockFileExists: false,
}));

vi.mock('@react-native-async-storage/async-storage', () => ({
  default: {
    getItem: vi.fn(async (k: string) => mocks.storage.get(k) ?? null),
    setItem: vi.fn(async (k: string, v: string) => {
      mocks.storage.set(k, v);
    }),
  },
}));

vi.mock('expo-device', () => ({
  supportedCpuArchitectures: ['arm64-v8a'],
}));

vi.mock('expo-file-system', () => {
  class MockFile {
    uri: string;
    contentUri: string;
    constructor(...parts: unknown[]) {
      this.uri = `file:///cache/${String(parts[parts.length - 1])}`;
      this.contentUri = `content://com.husainlab.pillion.provider/cache/${String(parts[parts.length - 1])}`;
    }
    get exists() {
      return mocks.mockFileExists;
    }
    delete() {
      mocks.mockDelete();
    }
    static createDownloadTask(...args: unknown[]) {
      return mocks.mockCreateDownloadTask(...args);
    }
  }
  return {
    File: MockFile,
    Paths: { cache: 'file:///cache' },
  };
});

vi.mock('expo-intent-launcher', () => ({
  ActivityAction: {
    MANAGE_UNKNOWN_APP_SOURCES: 'android.settings.MANAGE_UNKNOWN_APP_SOURCES',
  },
  startActivityAsync: mocks.mockStartActivityAsync,
}));

describe('in-app GitHub Releases updater state', () => {
  beforeEach(() => {
    mocks.storage.clear();
    mocks.mockDelete.mockReset();
    mocks.mockCreateDownloadTask.mockReset();
    mocks.mockStartActivityAsync.mockReset();
    mocks.mockFileExists = false;
    resetUpdateStateForTests();
  });

  it('discovers a newer release APK matching device ABI', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => [
          {
            tag_name: 'v1.4.2',
            html_url: 'https://github.com/husainlab/Bike_Companion_app/releases/tag/v1.4.2',
            body: 'Improved sunlight contrast',
            assets: [
              {
                name: 'Pillion-1.4.2.apk',
                browser_download_url: 'https://example.com/Pillion-1.4.2.apk',
                url: 'https://api.example.com/1',
              },
              {
                name: 'Pillion-1.4.2-arm64.apk',
                browser_download_url: 'https://example.com/Pillion-1.4.2-arm64.apk',
                url: 'https://api.example.com/2',
              },
            ],
          },
        ],
      }))
    );

    const found = await checkForAppUpdate(true);
    expect(found).toEqual({
      version: '1.4.2',
      apk: 'https://example.com/Pillion-1.4.2-arm64.apk',
      page: 'https://github.com/husainlab/Bike_Companion_app/releases/tag/v1.4.2',
      notes: 'Improved sunlight contrast',
    });
    expect(getUpdate().available?.version).toBe('1.4.2');
  });

  it('falls back to /tags when release assets are absent', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
      .mockResolvedValueOnce({ ok: true, json: async () => [{ name: 'v1.5.0' }] });
    vi.stubGlobal('fetch', fetchMock);

    const found = await checkForAppUpdate(true);
    expect(found?.version).toBe('1.5.0');
    expect(found?.apk).toContain('/raw/main/Pillion-release.apk');
  });

  it('supports putOffUpdate and setUpdateCheck', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => [
          {
            tag_name: 'v1.9.0',
            html_url: 'https://example.com/rel',
            assets: [
              {
                name: 'Pillion-1.9.0.apk',
                browser_download_url: 'https://example.com/Pillion-1.9.0.apk',
                url: 'https://api.example.com/9',
              },
            ],
          },
        ],
      }))
    );

    await checkForAppUpdate(true);
    expect(getUpdate().available?.version).toBe('1.9.0');
    putOffUpdate();
    expect(getUpdate().available).toBeNull();
    expect(await checkForAppUpdate(false)).toBeNull();

    await setUpdateCheck(false);
    expect(getUpdate().enabled).toBe(false);
  });

  it('downloads APK with progress, cleans stale file, and opens unknown-sources settings on install error', async () => {
    mocks.mockFileExists = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        json: async () => [
          {
            tag_name: 'v2.0.0',
            html_url: 'https://example.com/rel',
            assets: [
              {
                name: 'Pillion-2.0.0.apk',
                browser_download_url: 'https://example.com/Pillion-2.0.0.apk',
                url: 'https://api.example.com/20',
              },
            ],
          },
        ],
      }))
    );

    await checkForAppUpdate(true);

    mocks.mockCreateDownloadTask.mockImplementation((_url: string, dest: unknown, opts: { onProgress?: (p: { bytesWritten: number; totalBytes: number }) => void }) => ({
      downloadAsync: async () => {
        opts?.onProgress?.({ bytesWritten: 50, totalBytes: 100 });
        return dest;
      },
    }));

    mocks.mockStartActivityAsync.mockResolvedValueOnce({ resultCode: -1 });
    await installUpdate();

    expect(mocks.mockDelete).toHaveBeenCalledTimes(1);
    expect(getUpdate().progress).toBe(1);
    expect(mocks.mockStartActivityAsync).toHaveBeenCalledWith(
      'android.intent.action.VIEW',
      expect.objectContaining({
        type: 'application/vnd.android.package-archive',
        flags: 1 | 268435456,
      })
    );

    mocks.mockStartActivityAsync
      .mockRejectedValueOnce(new Error('Permission denied'))
      .mockResolvedValueOnce({ resultCode: 0 });
    await expect(installUpdate()).rejects.toThrow('Permission denied');
    expect(mocks.mockStartActivityAsync).toHaveBeenLastCalledWith(
      'android.settings.MANAGE_UNKNOWN_APP_SOURCES',
      { data: 'package:com.husainlab.pillion' }
    );
  });
});
