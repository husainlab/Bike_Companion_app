/**
 * Structural sharing helper that preserves prior object/array references when
 * deep-equal, preventing avoidable React re-renders on SWR refreshes.
 */
export function keepEqual<T>(prev: T, next: T): T {
  if (Object.is(prev, next)) return prev;
  if (
    typeof prev !== 'object' ||
    typeof next !== 'object' ||
    prev === null ||
    next === null
  ) {
    return next;
  }

  if (Array.isArray(prev) && Array.isArray(next)) {
    if (prev.length !== next.length) return next;
    let changed = false;
    const merged = new Array<unknown>(next.length);
    for (let i = 0; i < next.length; i++) {
      const item = keepEqual(prev[i], next[i]);
      merged[i] = item;
      if (!Object.is(item, prev[i])) changed = true;
    }
    return (changed ? merged : prev) as T;
  }

  if (Array.isArray(prev) !== Array.isArray(next)) return next;

  const prevObj = prev as Record<string, unknown>;
  const nextObj = next as Record<string, unknown>;
  const prevKeys = Object.keys(prevObj);
  const nextKeys = Object.keys(nextObj);
  if (prevKeys.length !== nextKeys.length) return next;

  let changed = false;
  const out: Record<string, unknown> = {};
  for (const key of nextKeys) {
    if (!Object.prototype.hasOwnProperty.call(prevObj, key)) {
      return next;
    }
    const mergedVal = keepEqual(prevObj[key], nextObj[key]);
    out[key] = mergedVal;
    if (!Object.is(mergedVal, prevObj[key])) changed = true;
  }
  return (changed ? out : prev) as T;
}

