/**
 * StatusBadge — maps payment/payout states (STATE-MACHINES.md §1/§2) onto the
 * semantic color scale. Unknown future states (contract codes are
 * additive-only) degrade to the neutral style instead of crashing.
 */

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { palette, radius, spacing, typography } from './theme';

export type StatusTone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

const TONES: Record<StatusTone, { fg: string; bg: string }> = {
  success: { fg: palette.success, bg: palette.successSoft },
  warning: { fg: palette.warning, bg: palette.warningSoft },
  danger: { fg: palette.danger, bg: palette.dangerSoft },
  neutral: { fg: palette.textMuted, bg: palette.neutralSoft },
  info: { fg: palette.brand, bg: palette.brandSoft },
};

/** Payment states (docs/STATE-MACHINES.md §1). */
const PAYMENT_TONES: Record<string, StatusTone> = {
  SUCCEEDED: 'success',
  PROCESSING: 'info',
  PENDING_PROVIDER: 'warning',
  CREATED: 'neutral',
  FAILED: 'danger',
  EXPIRED: 'neutral',
  REVERSED: 'warning',
  BLOCKED: 'danger',
  CANCELLED: 'neutral',
};

/** Payout states (docs/STATE-MACHINES.md §2). */
const PAYOUT_TONES: Record<string, StatusTone> = {
  SUCCEEDED: 'success',
  SENT: 'info',
  PROCESSING: 'info',
  PENDING_RISK: 'warning',
  CREATED: 'neutral',
  FAILED: 'danger',
  RETURNED: 'warning',
  BLOCKED: 'danger',
  CANCELLED: 'neutral',
};

/** Resolves the semantic tone for any payment or payout state string. */
export function statusTone(state: string): StatusTone {
  return PAYMENT_TONES[state] ?? PAYOUT_TONES[state] ?? 'neutral';
}

export interface StatusBadgeProps {
  state: string;
  testID?: string | undefined;
}

export function StatusBadge({ state, testID }: StatusBadgeProps) {
  const tone = TONES[statusTone(state)];
  return (
    <View testID={testID} style={[styles.badge, { backgroundColor: tone.bg }]}>
      <Text style={[styles.label, { color: tone.fg }]}>{state}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    paddingHorizontal: spacing.sm + 2,
    paddingVertical: spacing.xs + 1,
    borderRadius: radius.pill,
    alignSelf: 'flex-start',
  },
  label: {
    fontSize: typography.caption,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
});
