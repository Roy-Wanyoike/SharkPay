/**
 * ErrorView — renders failures with the API error envelope's machine-readable
 * fields (code, request_id) so users can quote them to support; accepts any
 * thrown value and degrades gracefully for non-ApiError failures.
 */

import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { SharkPayError } from '../api/errors';
import { Button } from './Button';
import { palette, radius, spacing, typography } from './theme';

export interface ErrorViewProps {
  /** The thrown value (typically a SharkPayError from the API client). */
  error: unknown;
  /** Optional pre-formatted message (wins over `error`). */
  message?: string;
  onRetry?: () => void;
  onDismiss?: () => void;
  testID?: string | undefined;
}

export function errorViewParts(error: unknown): {
  title: string;
  detail: string | null;
  code: string | null;
  requestId: string | null;
} {
  if (error instanceof SharkPayError) {
    return {
      title: error.message,
      detail: null,
      code: error.code,
      requestId: error.requestId ?? null,
    };
  }
  if (error instanceof Error) {
    return { title: error.message, detail: null, code: null, requestId: null };
  }
  return { title: 'Something went wrong.', detail: null, code: null, requestId: null };
}

export function ErrorView({ error, message, onRetry, onDismiss, testID }: ErrorViewProps) {
  const parts = errorViewParts(error);
  const title = message ?? parts.title;
  return (
    <View testID={testID} style={styles.container}>
      <View style={styles.banner}>
        <Text style={styles.title} accessibilityRole="alert">
          {title}
        </Text>
        {parts.code !== null || parts.requestId !== null ? (
          <Text style={styles.meta}>
            {[parts.code, parts.requestId].filter((part) => part !== null).join(' · ')}
          </Text>
        ) : null}
        {parts.detail !== null ? <Text style={styles.detail}>{parts.detail}</Text> : null}
      </View>
      {onRetry !== undefined || onDismiss !== undefined ? (
        <View style={styles.actions}>
          {onRetry !== undefined ? (
            <Button label="Try again" variant="secondary" onPress={onRetry} />
          ) : null}
          {onDismiss !== undefined ? (
            <Button label="Dismiss" variant="ghost" onPress={onDismiss} />
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.md,
    padding: spacing.lg,
  },
  banner: {
    backgroundColor: palette.dangerSoft,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: palette.danger,
    padding: spacing.md,
    gap: spacing.xs,
  },
  title: {
    fontSize: typography.body,
    fontWeight: '600',
    color: palette.text,
  },
  meta: {
    fontSize: typography.caption,
    color: palette.danger,
    fontWeight: '700',
  },
  detail: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.md,
    justifyContent: 'center',
  },
});
