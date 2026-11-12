/**
 * Rewrites named imports from `lucide-react-native` into single-icon subpath
 * imports (`lucide-react-native/icons/<kebab>`) so Metro does not bundle the
 * entire ~1,900-icon barrel file.
 */
function toKebabCase(name) {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/([a-zA-Z])([0-9]+)/g, '$1-$2')
    .toLowerCase();
}

const PASSTHROUGH_EXPORTS = new Set([
  'Icon',
  'createLucideIcon',
  'LucideIcon',
  'LucideProps',
  'default',
]);

module.exports = function babelLucideTreeShake({ types: t }) {
  return {
    name: 'lucide-tree-shake',
    visitor: {
      ImportDeclaration(path) {
        if (path.node.source.value !== 'lucide-react-native') return;
        if (path.node.importKind === 'type') return;

        const replacements = [];
        const remaining = [];

        for (const spec of path.node.specifiers) {
          if (
            t.isImportSpecifier(spec) &&
            spec.importKind !== 'type' &&
            !PASSTHROUGH_EXPORTS.has(spec.imported.name)
          ) {
            const kebab = toKebabCase(spec.imported.name);
            replacements.push(
              t.importDeclaration(
                [t.importDefaultSpecifier(spec.local)],
                t.stringLiteral(`lucide-react-native/icons/${kebab}`)
              )
            );
          } else {
            remaining.push(spec);
          }
        }

        if (replacements.length === 0) return;
        if (remaining.length > 0) {
          replacements.push(
            t.importDeclaration(remaining, t.stringLiteral('lucide-react-native'))
          );
        }
        path.replaceWithMultiple(replacements);
      },
    },
  };
};

