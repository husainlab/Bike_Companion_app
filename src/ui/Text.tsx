import React from 'react';
import { Text as RNText, type TextProps as RNTextProps } from 'react-native';
import { resolveFontFamily, type TextVariant } from './fontClasses';

export interface TextProps extends RNTextProps {
  className?: string;
  variant?: TextVariant;
}

export function Text({
  className = '',
  style,
  variant,
  textBreakStrategy = 'simple',
  ...props
}: TextProps) {
  const fontFamily = resolveFontFamily(className, variant);
  const hasColorClass = /\btext-(foreground|primary|on-primary|accent|on-accent|danger|warning|white|black)\b/.test(
    className
  );
  const combinedClass = hasColorClass ? className : `text-foreground ${className}`;

  return (
    <RNText
      textBreakStrategy={textBreakStrategy}
      {...props}
      className={combinedClass.trim()}
      style={[{ fontFamily }, style]}
    />
  );
}

