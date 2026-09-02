/**
 * AmountKeypad — decimal amount entry for the Send/Payouts flows.
 *
 * The keypad edits a STRING ("12.5"); minor units are derived only through
 * `parseAmountToMinor` (src/money/parse.ts), never through a float. Keys that
 * would create an unrepresentable amount (more fractional digits than the
 * currency's exponent) are ignored — the component cannot construct an
 * invalid amount string beyond a syntactically partial one ("12.").
 */

import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { palette, radius, spacing, typography } from './theme';

export type KeypadKey = '0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9' | '.' | 'DEL';

const KEYS: readonly KeypadKey[] = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'DEL'];

/** Maximum whole-part digits (keeps BigInt math trivially in range). */
export const MAX_WHOLE_DIGITS = 12;

/**
 * Pure key-press reducer — the entire keypad behaviour in one testable
 * function. `value` is the current raw string; returns the next string.
 */
export function pressKey(value: string, key: KeypadKey, exponent: number): string {
  if (key === 'DEL') {
    return value.slice(0, -1);
  }
  if (key === '.') {
    if (exponent === 0 || value.includes('.')) {
      return value;
    }
    return value.length === 0 ? '0.' : `${value}.`;
  }
  const dotIndex = value.indexOf('.');
  const hasDot = dotIndex !== -1;
  const wholePart = hasDot ? value.slice(0, dotIndex) : value;
  const fractionPart = hasDot ? value.slice(dotIndex + 1) : '';

  if (hasDot && fractionPart.length >= exponent) {
    // Another fractional digit would be silently dropped by the currency.
    return value;
  }
  if (!hasDot) {
    if (wholePart.length >= MAX_WHOLE_DIGITS) {
      return value;
    }
    if (value === '0') {
      return key;
    }
  }
  return value + key;
}

export interface AmountKeypadProps {
  /** Raw keypad string being edited (e.g. "12.5"). */
  value: string;
  onChange(next: string): void;
  exponent: number;
  currency: string;
  /** Disabled keys render inert (loading states). */
  enabled?: boolean;
  testID?: string | undefined;
}

export function AmountKeypad({
  value,
  onChange,
  exponent,
  currency,
  enabled = true,
  testID,
}: AmountKeypadProps) {
  const empty = value.length === 0;
  return (
    <View testID={testID} style={styles.container}>
      <View style={styles.readout} accessibilityElementsHidden>
        {empty ? (
          <Text style={styles.placeholder}>0{exponent > 0 ? `.${'0'.repeat(Math.min(exponent, 2))}` : ''}</Text>
        ) : (
          <Text style={styles.typed}>
            <Text style={styles.currency}>{currency} </Text>
            {value}
          </Text>
        )}
      </View>
      <View style={styles.grid}>
        {KEYS.map((key) => (
          <Pressable
            key={key}
            testID={testID !== undefined ? `${testID}-key-${key.replace('.', 'dot')}` : undefined}
            accessibilityRole="button"
            accessibilityLabel={key === 'DEL' ? 'Delete digit' : key === '.' ? 'decimal point' : key}
            disabled={!enabled}
            onPress={() => onChange(pressKey(value, key, exponent))}
            style={({ pressed }) => [
              styles.key,
              !enabled && styles.keyDisabled,
              pressed && enabled ? styles.keyPressed : null,
            ]}
          >
            <Text style={styles.keyLabel}>{key === 'DEL' ? 'DEL' : key}</Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.lg,
  },
  readout: {
    minHeight: 56,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: spacing.md,
  },
  placeholder: {
    fontSize: typography.moneyMedium,
    fontWeight: '700',
    color: palette.textMuted,
    fontVariant: ['tabular-nums'],
  },
  typed: {
    fontSize: typography.moneyLarge,
    fontWeight: '700',
    color: palette.text,
    fontVariant: ['tabular-nums'],
  },
  currency: {
    fontSize: typography.body,
    fontWeight: '600',
    color: palette.textMuted,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
  },
  key: {
    width: '31%',
    aspectRatio: 1.6,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.md,
    borderRadius: radius.md,
    backgroundColor: palette.surface,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: palette.border,
  },
  keyPressed: {
    backgroundColor: palette.brandSoft,
    borderColor: palette.brand,
  },
  keyDisabled: {
    opacity: 0.5,
  },
  keyLabel: {
    fontSize: typography.title,
    fontWeight: '600',
    color: palette.text,
  },
});
