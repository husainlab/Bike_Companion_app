import { useEffect, useRef, useState } from 'react';
import type { QueryCtx } from '@/queries/cockpit';
import { getStore } from '@/state/app';
import { getDataVersion, useDataVersion } from '@/state/dataVersion';
import { useScreenReady } from '@/ui/shells/useScreenReady';
import { keepEqual } from './keepEqual';

interface CacheEntry<T> {
  data: T;
  version: number;
}

const screenCache = new Map<string, CacheEntry<unknown>>();

function cacheKey(screenId: string, params: readonly unknown[]): string {
  return `${screenId}:${JSON.stringify(params)}`;
}

export function clearScreenCache(): void {
  screenCache.clear();
}

export function preloadWhenIdle<T>(
  screenId: string,
  params: readonly unknown[],
  loader: (ctx: QueryCtx) => Promise<T>
): void {
  const key = cacheKey(screenId, params);
  const version = getDataVersion();
  const cached = screenCache.get(key);
  if (cached && cached.version === version) return;

  const schedule =
    typeof globalThis.requestIdleCallback === 'function'
      ? globalThis.requestIdleCallback
      : (cb: () => void) => setTimeout(cb, 50);

  schedule(() => {
    const ctx: QueryCtx = { store: getStore(), version: getDataVersion() };
    loader(ctx)
      .then((data) => {
        screenCache.set(key, { data, version: ctx.version });
      })
      .catch(() => {});
  });
}

export interface ScreenResult<T> {
  data: T | undefined;
  loading: boolean;
  error: Error | null;
  reload: () => void;
}

export function useScreen<T>(
  screenId: string,
  params: readonly unknown[],
  loader: (ctx: QueryCtx) => Promise<T>
): ScreenResult<T> {
  const version = useDataVersion();
  const ready = useScreenReady();
  const key = cacheKey(screenId, params);
  const initialHit = screenCache.get(key) as CacheEntry<T> | undefined;

  const [data, setData] = useState<T | undefined>(() => initialHit?.data);
  const [loading, setLoading] = useState<boolean>(() => !initialHit);
  const [error, setError] = useState<Error | null>(null);
  const [nonce, setNonce] = useState(0);
  const loaderRef = useRef(loader);

  useEffect(() => {
    loaderRef.current = loader;
  }, [loader]);

  useEffect(() => {
    if (!ready && !initialHit) return;
    let cancelled = false;
    const ctx: QueryCtx = { store: getStore(), version };

    loaderRef
      .current(ctx)
      .then((next) => {
        if (cancelled) return;
        setData((prev) => {
          const merged = prev !== undefined ? keepEqual(prev, next) : next;
          screenCache.set(key, { data: merged, version });
          return merged;
        });
        setError(null);
        setLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err instanceof Error ? err : new Error(String(err)));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [key, version, ready, nonce, initialHit]);

  return {
    data,
    loading,
    error,
    reload: () => setNonce((n) => n + 1),
  };
}
