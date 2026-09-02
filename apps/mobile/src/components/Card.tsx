/**
 * Card — the surface container of the design system.
 */

import React from 'react';
import { StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

import { palette, radius, spacing, typography } from './theme';

export interface CardProps {
  title?: string;
  /** Right-aligned slot in the header (e.g. a StatusBadge). */
  accessory?: React.ReactNode;
  children?: React.ReactNode;
  footer?: React.ReactNode;
  testID?: string | undefined;
  style?: StyleProp<ViewStyle>;
}

export function Card({ title, accessory, children, footer, testID, style }: CardProps) {
  const hasHeader = title !== undefined || accessory !== undefined;
  return (
    <View testID={testID} style={[styles.card, style]}>
      {hasHeader ? (
        <View style={styles.header}>
          {title !== undefined ? (
            <Text style={styles.title} accessibilityRole="header">
              {title}
            </Text>
          ) : null}
          {accessory !== undefined ? <View>{accessory}</View> : null}
        </View>
      ) : null}
      {children !== undefined ? <View style={styles.body}>{children}</View> : null}
      {footer !== undefined ? <View style={styles.footer}>{footer}</View> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: palette.surface,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: palette.border,
    padding: spacing.lg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    marginBottom: spacing.md,
  },
  title: {
    fontSize: typography.label,
    fontWeight: '700',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
    color: palette.textMuted,
    flexShrink: 1,
  },
  body: {
    gap: spacing.sm,
  },
  footer: {
    marginTop: spacing.md,
    paddingTop: spacing.md,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: palette.border,
  },
});
