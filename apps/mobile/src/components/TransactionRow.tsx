/**
 * TransactionRow — one activity line (payment intent or payout) on the Home
 * and Activity screens. Money is rendered exclusively through MoneyDisplay
 * (BigInt-safe); the row itself is display-only.
 */

import React from 'react';
import { Pressable, StyleSheet, Text, View, type ViewStyle } from 'react-native';

import { MoneyDisplay } from './MoneyDisplay';
import { StatusBadge } from './StatusBadge';
import { palette, spacing, typography } from './theme';

export interface TransactionRowProps {
  /** Row headline, e.g. "Payment" / "Payout" / "M-Pesa payout". */
  title: string;
  /** Supporting line, e.g. destination or id. */
  subtitle?: string;
  /** Timestamp label (already human-formatted). */
  timestamp?: string;
  amountMinor: number | bigint;
  exponent: number;
  currency: string;
  /** 'in' = money into a wallet (credit), 'out' = money leaving (debit). */
  direction: 'in' | 'out';
  state: string;
  onPress?: () => void;
  testID?: string | undefined;
}

export function TransactionRow({
  title,
  subtitle,
  timestamp,
  amountMinor,
  exponent,
  currency,
  direction,
  state,
  onPress,
  testID,
}: TransactionRowProps) {
  const body = (
    <View style={styles.row}>
      <View style={styles.left}>
        <Text style={styles.title} numberOfLines={1}>
          {title}
        </Text>
        {subtitle !== undefined ? (
          <Text style={styles.subtitle} numberOfLines={1}>
            {subtitle}
          </Text>
        ) : null}
        {timestamp !== undefined ? <Text style={styles.timestamp}>{timestamp}</Text> : null}
      </View>
      <View style={styles.right}>
        <MoneyDisplay
          amountMinor={amountMinor}
          exponent={exponent}
          currency={currency}
          size="sm"
          tone={direction === 'in' ? 'positive' : 'default'}
          withSign={direction === 'in'}
          testID={testID !== undefined ? `${testID}-amount` : undefined}
        />
        <StatusBadge
          state={state}
          testID={testID !== undefined ? `${testID}-status` : undefined}
        />
      </View>
    </View>
  );

  if (onPress === undefined) {
    return (
      <View testID={testID} style={styles.container}>
        {body}
      </View>
    );
  }
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={title}
      onPress={onPress}
      style={({ pressed }) => [
        styles.container,
        pressed ? ({ backgroundColor: palette.neutralSoft } as ViewStyle) : null,
      ]}
    >
      {body}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: palette.border,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: spacing.md,
  },
  left: {
    flexShrink: 1,
    gap: 2,
  },
  title: {
    fontSize: typography.body,
    fontWeight: '600',
    color: palette.text,
  },
  subtitle: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  timestamp: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  right: {
    alignItems: 'flex-end',
    gap: spacing.xs,
  },
});
