const ALLOWED_PROTOCOLS = new Set(['https:', 'http:', 'pillion:']);

export function isSafeUrl(raw: string): boolean {
  try {
    const parsed = new URL(raw);
    return ALLOWED_PROTOCOLS.has(parsed.protocol);
  } catch {
    return false;
  }
}

