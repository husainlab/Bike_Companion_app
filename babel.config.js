module.exports = function (api) {
  api.cache(true);
  return {
    presets: [
      ['babel-preset-expo', { jsxImportSource: 'nativewind', lazyImports: true }],
      'nativewind/babel',
    ],
    plugins: ['./scripts/babel-lucide-tree-shake'],
  };
};

