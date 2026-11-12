import React from 'react';
import Svg, { Circle, Line } from 'react-native-svg';

interface PillionMarkProps {
  size?: number;
  color?: string;
}

/**
 * Single-color Pillion logomark: two staggered pillion bars linked by a tandem
 * strut, engineered to remain legible at both 16px favicon and 400px header scale.
 */
export function PillionMark({ size = 28, color = '#FF5A1F' }: PillionMarkProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 100 100" accessibilityLabel="Pillion mark">
      <Line
        x1={18}
        y1={30}
        x2={72}
        y2={30}
        stroke={color}
        strokeWidth={14}
        strokeLinecap="round"
      />
      <Line
        x1={28}
        y1={70}
        x2={82}
        y2={70}
        stroke={color}
        strokeWidth={14}
        strokeLinecap="round"
      />
      <Line
        x1={62}
        y1={30}
        x2={38}
        y2={70}
        stroke={color}
        strokeWidth={14}
        strokeLinecap="round"
      />
      <Circle cx={18} cy={30} r={8} fill={color} />
      <Circle cx={82} cy={70} r={8} fill={color} />
    </Svg>
  );
}

