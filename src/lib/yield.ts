/**
 * Yields control back to the JS event loop so long-running foreground work does
 * not stall Android touch input or UI transitions.
 */
export function slice(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

export async function processInSlices<T, R>(
  items: readonly T[],
  chunkSize: number,
  fn: (item: T, index: number) => R
): Promise<R[]> {
  const out: R[] = [];
  const step = Math.max(1, chunkSize);
  for (let i = 0; i < items.length; i++) {
    out.push(fn(items[i], i));
    if ((i + 1) % step === 0 && i + 1 < items.length) {
      await slice();
    }
  }
  return out;
}

