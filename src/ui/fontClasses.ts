export const FONT_FAMILIES = {
  regular: 'AtkinsonHyperlegible_400Regular',
  bold: 'AtkinsonHyperlegible_700Bold',
  italic: 'AtkinsonHyperlegible_400Regular_Italic',
  mono: 'JetBrainsMono_500Medium',
  monoBold: 'JetBrainsMono_700Bold',
} as const;

export type TextVariant = keyof typeof FONT_FAMILIES;

export function resolveFontFamily(className?: string, variant?: TextVariant): string {
  if (variant) return FONT_FAMILIES[variant];
  if (!className) return FONT_FAMILIES.regular;
  const tokens = className.split(/\s+/);
  if (tokens.includes('font-mono-bold')) return FONT_FAMILIES.monoBold;
  if (tokens.includes('font-mono')) {
    return tokens.includes('font-bold') || tokens.includes('font-extrabold')
      ? FONT_FAMILIES.monoBold
      : FONT_FAMILIES.mono;
  }
  if (tokens.includes('font-bold') || tokens.includes('font-semibold') || tokens.includes('font-extrabold')) {
    return FONT_FAMILIES.bold;
  }
  if (tokens.includes('italic')) {
    return FONT_FAMILIES.italic;
  }
  return FONT_FAMILIES.regular;
}
