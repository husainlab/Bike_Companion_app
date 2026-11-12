export function formatPercent(ratio: number): string {
  const clamped = Math.max(0, Math.min(1, Number.isFinite(ratio) ? ratio : 0));
  return `${Math.round(clamped * 100)}%`;
}

export function shortAddress(address: string): string {
  const clean = address.trim();
  if (clean.length <= 12) return clean;
  return `${clean.slice(0, 8)}...${clean.slice(-5)}`;
}

