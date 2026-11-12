const { defineConfig } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');

module.exports = defineConfig([
  expoConfig,
  {
    files: ['*.config.js'],
    languageOptions: {
      globals: {
        __dirname: 'readonly',
        module: 'readonly',
        require: 'readonly',
      },
    },
  },
  {
    ignores: [
      'dist/*',
      'android/*',
      'ios/*',
      '.expo/*',
      'node_modules/*',
      'plugins/*',
      'scripts/*',
      'modules/*/android/build/*',
    ],
  },
]);
