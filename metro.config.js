const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');
const { withNativeWind } = require('nativewind/metro');

const config = getDefaultConfig(__dirname);

// Ensure lucide-react-native/icons/* subpath imports resolve cleanly even if package exports shift
const originalResolveRequest = config.resolver.resolveRequest;
config.resolver.resolveRequest = (context, moduleName, platform) => {
  if (moduleName.startsWith('lucide-react-native/icons/')) {
    const iconName = moduleName.replace('lucide-react-native/icons/', '');
    const iconPath = path.resolve(
      __dirname,
      'node_modules/lucide-react-native/dist/esm/icons',
      `${iconName}.mjs`
    );
    return {
      filePath: iconPath,
      type: 'sourceFile',
    };
  }
  if (originalResolveRequest) {
    return originalResolveRequest(context, moduleName, platform);
  }
  return context.resolveRequest(context, moduleName, platform);
};

module.exports = withNativeWind(config, { input: './global.css' });

