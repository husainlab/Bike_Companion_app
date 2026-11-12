const { withAppBuildGradle } = require('expo/config-plugins');

const SIGNING_MARKER = '// PILLION_RELEASE_SIGNING';

/**
 * Configures Android release signing from environment variables while falling
 * back to debug signing locally if no release keystore is present.
 */
module.exports = function withReleaseSigning(config) {
  return withAppBuildGradle(config, (mod) => {
    let contents = mod.modResults.contents;

    if (!contents.includes(SIGNING_MARKER)) {
      const releaseConfigBlock = `        ${SIGNING_MARKER}
        release {
            def storePath = System.getenv("RELEASE_STORE_FILE") ?: "../../credentials/release.keystore"
            def resolvedStore = file(storePath)
            if (resolvedStore.exists()) {
                storeFile resolvedStore
                storePassword System.getenv("RELEASE_STORE_PASSWORD") ?: "Pillion_c7f0c1ca5b2dfcde602c"
                keyAlias System.getenv("RELEASE_KEY_ALIAS") ?: "pillion-release"
                keyPassword System.getenv("RELEASE_KEY_PASSWORD") ?: "Pillion_c7f0c1ca5b2dfcde602c"
                v1SigningEnabled true
                v2SigningEnabled true
            }
        }`;

      contents = contents.replace(
        /signingConfigs\s*\{\s*\n\s*debug\s*\{[\s\S]*?\n\s*\}/,
        (match) => `${match}\n${releaseConfigBlock}`
      );
    }

    contents = contents.replace(
      /(buildTypes\s*\{[\s\S]*?\n\s*release\s*\{[\s\S]*?)signingConfig\s+signingConfigs\.debug/,
      (match, prefix) =>
        `${prefix}signingConfig (signingConfigs.release.storeFile != null && signingConfigs.release.storeFile.exists() ? signingConfigs.release : signingConfigs.debug)`
    );

    mod.modResults.contents = contents;
    return mod;
  });
};

