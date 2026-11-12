import { useEffect, useState } from 'react';

/**
 * Defers heavy cold screen commit until after the first navigation frame so
 * Android transition animations start without contention.
 */
export function useScreenReady(): boolean {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const raf = requestAnimationFrame(() => {
      timer = setTimeout(() => {
        if (active) setReady(true);
      }, 0);
    });
    return () => {
      active = false;
      cancelAnimationFrame(raf);
      if (timer) clearTimeout(timer);
    };
  }, []);

  return ready;
}
