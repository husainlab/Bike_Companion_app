import fs from 'node:fs';
import path from 'node:path';

export function bumpPatch(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)$/.exec(String(version).trim());
  if (!match) {
    throw new Error(`Invalid semver version: ${version}`);
  }
  return `${match[1]}.${match[2]}.${Number(match[3]) + 1}`;
}

export function bumpProjectVersions(rootDir = process.cwd()) {
  const appJsonPath = path.join(rootDir, 'app.json');
  const pkgJsonPath = path.join(rootDir, 'package.json');

  const appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
  const pkgJson = JSON.parse(fs.readFileSync(pkgJsonPath, 'utf8'));

  const currentVersion = appJson.expo?.version ?? pkgJson.version;
  const newVersion = bumpPatch(currentVersion);
  const nextVersionCode = Number(appJson.expo?.android?.versionCode ?? 0) + 1;

  appJson.expo.version = newVersion;
  appJson.expo.android.versionCode = nextVersionCode;
  pkgJson.version = newVersion;

  fs.writeFileSync(appJsonPath, `${JSON.stringify(appJson, null, 2)}\n`);
  fs.writeFileSync(pkgJsonPath, `${JSON.stringify(pkgJson, null, 2)}\n`);

  return { newVersion, versionCode: nextVersionCode };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const { newVersion, versionCode } = bumpProjectVersions();
  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(
      process.env.GITHUB_OUTPUT,
      `new_version=${newVersion}\nversion_code=${versionCode}\n`
    );
  }
  console.log(`Bumped version to ${newVersion} (versionCode ${versionCode})`);
}

