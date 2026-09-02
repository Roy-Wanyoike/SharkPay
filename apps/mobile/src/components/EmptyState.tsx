/**
 * EmptyState — friendly zero-data placeholder (no assets required).
 */

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { Button } from './Button';
import { palette, spacing, typography } from './theme';

export interface EmptyStateProps {
  title: string;
  message?: string;
  actionLabel?: string;
  onAction?: () => void;
  testID?: string | undefined;
}

export function EmptyState({ title, message, actionLabel, onAction, testID }: EmptyStateProps) {
  return (
    <View testID={testID} style={styles.container}>
      <View style={styles.glyph} accessibilityElementsHidden>
        <Text style={styles.glyphText}>SP</Text>
      </View>
      <Text style={styles.title}>{title}</Text>
      {message !== undefined ? <Text style={styles.message}>{message}</Text> : null}
      {actionLabel !== undefined && onAction !== undefined ? (
        <Button label={actionLabel} variant="secondary" onPress={onAction} style={styles.action} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    padding: spacing.xxl,
    gap: spacing.md,
  },
  glyph: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: palette.brandSoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.xs,
  },
  glyphText: {
    color: palette.brand,
    fontWeight: '800',
    fontSize: typography.heading,
    letterSpacing: 1,
  },
  title: {
    fontSize: typography.heading,
    fontWeight: '700',
    color: palette.text,
    textAlign: 'center',
  },
  message: {
    fontSize: typography.body,
    color: palette.textMuted,
    textAlign: 'center',
    maxWidth: 320,
  },
  action: {
    marginTop: spacing.sm,
  },
});
