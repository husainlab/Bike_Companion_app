const fs = require('fs');
const path = require('path');

/**
 * Extracts CSS custom properties from global.css `:root` so Tailwind color
 * tokens stay single-sourced in global.css while supporting `/opacity` modifiers.
 */
function extractCssTokens() {
  const cssPath = path.join(__dirname, 'global.css');
  const css = fs.readFileSync(cssPath, 'utf8');
  const rootMatch = css.match(/:root\s*\{([\s\S]*?)\}/);
  const colors = {};
  if (!rootMatch) return colors;

  const regex = /--color-([a-z0-9-]+)\s*:\s*([^;]+);/g;
  let match;
  while ((match = regex.exec(rootMatch[1])) !== null) {
    const [, name, value] = match;
    if (value.trim().startsWith('rgb(')) {
      colors[name] = `var(--color-${name})`;
    } else {
      colors[name] = `rgb(var(--color-${name}) / <alpha-value>)`;
    }
  }
  return colors;
}

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./app/**/*.{ts,tsx}', './src/**/*.{ts,tsx}'],
  presets: [require('nativewind/preset')],
  darkMode: 'class',
  theme: {
    extend: {
      colors: extractCssTokens(),
      // Override NativeWind's default 1rem = 14px scaling with explicit px steps
      spacing: {
        '0': '0px',
        '1': '4px',
        '2': '8px',
        '3': '12px',
        '3.5': '14px',
        '4': '16px',
        '5': '20px',
        '6': '24px',
        '7': '28px',
        '8': '32px',
        '10': '40px',
        '12': '48px',
        '14': '56px',
        '16': '64px',
        xs: '4px',
        sm: '8px',
        md: '14px',
        lg: '20px',
        xl: '32px',
        '2xl': '48px',
        hit: '56px',
      },
      borderRadius: {
        none: '0px',
        xs: '3px',
        sm: '6px',
        md: '10px',
        pill: '999px',
        full: '999px',
      },
      fontSize: {
        xs: ['12px', { lineHeight: '16px' }],
        sm: ['14px', { lineHeight: '20px' }],
        base: ['16px', { lineHeight: '22px' }],
        lg: ['19px', { lineHeight: '26px' }],
        xl: ['23px', { lineHeight: '30px' }],
        '2xl': ['28px', { lineHeight: '34px' }],
        '3xl': ['36px', { lineHeight: '40px' }],
      },
      lineHeight: {
        tight: '18px',
        snug: '22px',
        normal: '24px',
        relaxed: '28px',
      },
    },
  },
  plugins: [],
};

