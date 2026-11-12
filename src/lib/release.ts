export const ABI_ASSET = {
  'arm64-v8a': 'arm64',
  'armeabi-v7a': 'armv7',
  x86_64: 'x86_64',
} as const;

export type SupportedAbi = keyof typeof ABI_ASSET;
export type AssetAbiSuffix = (typeof ABI_ASSET)[SupportedAbi];

export interface ParsedRelease {
  version: string;
  tag: string;
  page: string;
  notes: string;
  publishedAt: string | null;
  apks: {
    default: string | null;
    byAbi: Partial<Record<AssetAbiSuffix, string>>;
  };
  apiApks: {
    default: string | null;
    byAbi: Partial<Record<AssetAbiSuffix, string>>;
  };
}

const SEMVER_RE = /^v?(\d+)\.(\d+)\.(\d+)$/;

export function parseSemver(input: string): [number, number, number] | null {
  const match = SEMVER_RE.exec(input.trim());
  if (!match) return null;
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

export function isNewer(latest: string, current: string): boolean {
  const a = parseSemver(latest);
  const b = parseSemver(current);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) {
    if (a[i] > b[i]) return true;
    if (a[i] < b[i]) return false;
  }
  return false;
}

interface GitHubAssetLike {
  name?: unknown;
  browser_download_url?: unknown;
  url?: unknown;
}

interface GitHubReleaseLike {
  draft?: unknown;
  prerelease?: unknown;
  tag_name?: unknown;
  html_url?: unknown;
  body?: unknown;
  published_at?: unknown;
  assets?: unknown;
}

export function parseRelease(json: unknown, appPrefix = 'Pillion'): ParsedRelease | null {
  if (!json || typeof json !== 'object') return null;
  const rel = json as GitHubReleaseLike;

  if (rel.draft === true || rel.prerelease === true) return null;
  if (typeof rel.tag_name !== 'string') return null;

  const parsed = parseSemver(rel.tag_name);
  if (!parsed) return null;
  const version = `${parsed[0]}.${parsed[1]}.${parsed[2]}`;

  const page = typeof rel.html_url === 'string' ? rel.html_url : '';
  const notes = typeof rel.body === 'string' ? rel.body : '';
  const publishedAt = typeof rel.published_at === 'string' ? rel.published_at : null;

  const apks: ParsedRelease['apks'] = { default: null, byAbi: {} };
  const apiApks: ParsedRelease['apiApks'] = { default: null, byAbi: {} };

  const assets = Array.isArray(rel.assets) ? (rel.assets as GitHubAssetLike[]) : [];
  const prefixLower = appPrefix.toLowerCase();
  const suffixes = Object.values(ABI_ASSET) as AssetAbiSuffix[];

  for (const asset of assets) {
    if (!asset || typeof asset.name !== 'string') continue;
    const name = asset.name;
    const nameLower = name.toLowerCase();
    if (!nameLower.endsWith('.apk')) continue;

    const browserUrl =
      typeof asset.browser_download_url === 'string' && asset.browser_download_url.length > 0
        ? asset.browser_download_url
        : null;
    const apiUrl =
      typeof asset.url === 'string' && asset.url.length > 0 ? asset.url : null;

    if (!browserUrl && !apiUrl) continue;

    let matchedAbi: AssetAbiSuffix | null = null;
    for (const suffix of suffixes) {
      if (
        nameLower === `${prefixLower}-${version}-${suffix}.apk` ||
        nameLower.endsWith(`-${suffix}.apk`)
      ) {
        matchedAbi = suffix;
        break;
      }
    }

    if (matchedAbi) {
      if (browserUrl) apks.byAbi[matchedAbi] = browserUrl;
      if (apiUrl) apiApks.byAbi[matchedAbi] = apiUrl;
      continue;
    }

    if (
      nameLower === `${prefixLower}-${version}.apk` ||
      nameLower === `${prefixLower}-release.apk`
    ) {
      if (nameLower === `${prefixLower}-${version}.apk` || !apks.default) {
        if (browserUrl) apks.default = browserUrl;
      }
      if (nameLower === `${prefixLower}-${version}.apk` || !apiApks.default) {
        if (apiUrl) apiApks.default = apiUrl;
      }
    }
  }

  if (!apks.default && Object.keys(apks.byAbi).length === 0) {
    return null;
  }

  return {
    version,
    tag: rel.tag_name,
    page,
    notes,
    publishedAt,
    apks,
    apiApks,
  };
}

export function apkFor(
  release: ParsedRelease,
  abis: readonly string[] | null | undefined
): string | null {
  if (abis && abis.length > 0) {
    for (const abi of abis) {
      const mapped = ABI_ASSET[abi as SupportedAbi];
      if (mapped && release.apks.byAbi[mapped]) {
        return release.apks.byAbi[mapped] ?? null;
      }
    }
  }
  if (release.apks.default) return release.apks.default;
  const firstAbi = Object.values(release.apks.byAbi)[0];
  return firstAbi ?? null;
}

