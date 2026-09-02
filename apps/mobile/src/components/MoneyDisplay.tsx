/**
 * MoneyDisplay — the ONLY sanctioned way to render an amount.
 *
 * Money-safety (ADR 001 §4, mirrored from apps/web's formatMinor):
 * - `bigint` inputs are formatted exactly (int64-safe, incl. 2^53+1).
 * - `number` inputs must be SAFE integers; anything else THROWS — a display
 *   path must never silently round money (see src/money/format.ts).
 * - No float ever touches an amount: formatting is pure string arithmetic.
 */

import React from 'react';
import { StyleSheet, Text, type TextProps } from 'react-native';

import { formatMinor } from '../money/format';
import { palette, typography } from './theme';

export interface MoneyDisplayProps {
  /** Minor units; `bigint` preferred (exact for the full int64 range). */
  amountMinor: number | bigint;
  /** Minor-unit exponent of the currency (2 for KES/USD/…, 6 for USDC/USDT). */
  exponent: number;
  /** Currency code rendered before the amount (e.g. "KES 1,500.00"). */
  currency?: string;
  size?: 'sm' | 'md' | 'lg';
  /** Color semantics: incoming money green, outgoing/debits default/red. */
  tone?: 'default' | 'positive' | 'negative' | 'muted';
  /** Render an explicit "+" for positive amounts (credit rows). */
  withSign?: boolean;
  /** Monospace-ish tabular alignment for lists. */
  testID?: string | undefined;
}

const SIZE_STYLES = {
  sm: { fontSize: typography.moneySmall, currencySize: typography.caption },
  md: { fontSize: typography.moneyMedium, currencySize: typography.label },
  lg: { fontSize: typography.moneyLarge, currencySize: typography.body },
} as const;

const TONE_COLORS = {
  default: palette.text,
  positive: palette.success,
  negative: palette.danger,
  muted: palette.textMuted,
} as const;

export function MoneyDisplay({
  amountMinor,
  exponent,
  currency,
  size = 'md',
  tone = 'default',
  withSign = false,
  testID,
  ...textProps
}: MoneyDisplayProps) {
  // May throw by design on unsafe/corrupt amounts (fail loudly, never
  // display invented digits) — callers wrap in error boundaries.
  const formatted = formatMinor(amountMinor, exponent, { withSign });
  const sizeStyle = SIZE_STYLES[size];
  const color = TONE_COLORS[tone];

  return (
    <Text
      testID={testID}
      accessibilityLabel={currency !== undefined ? `${currency} ${formatted}` : formatted}
      style={[styles.base, { fontSize: sizeStyle.fontSize, color }]}
      {...textProps}
    >
      {currency !== undefined ? (
        <Text style={[styles.currency, { fontSize: sizeStyle.currencySize, color }]}>{currency}</Text>
      ) : null}
      {formatted}
    </Text>
  );
}

const styles = StyleSheet.create({
  base: {
    fontVariant: ['tabular-nums'],
    fontWeight: '700',
  },
  currency: {
    fontWeight: '600',
    marginRight: 4,
    opacity: 0.85,
  },
});
