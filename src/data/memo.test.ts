import { describe, expect, it, vi } from 'vitest';
import { dataChanged, getDataVersion } from '@/state/dataVersion';
import { memoStore } from './memo';
import { MemoryStore } from './memory';

describe('memoStore versioned cache', () => {
  it('caches repeated reads for identical versions and invalidates on dataChanged()', async () => {
    const base = new MemoryStore();
    const getSettingsSpy = vi.spyOn(base, 'getSettings');
    const cached = memoStore(base, getDataVersion);

    await cached.getSettings();
    await cached.getSettings();
    expect(getSettingsSpy).toHaveBeenCalledTimes(1);

    dataChanged();
    await cached.getSettings();
    expect(getSettingsSpy).toHaveBeenCalledTimes(2);
  });
});

