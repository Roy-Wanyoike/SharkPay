/**
 * Screen — shared page scaffold: safe-area padding, optional scroll, title.
 */

import React, { type ReactNode } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type RefreshControlProps,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { palette, spacing, typography } from './theme';

export interface ScreenProps {
  title?: string;
  subtitle?: string;
  children: ReactNode;
  scroll?: boolean;
  /** Extra header slot (e.g. back-adjacent actions). */
  headerRight?: ReactNode;
  footer?: ReactNode;
  /** Pull-to-refresh control (passed through to the ScrollView). */
  refreshControl?: React.ReactElement<RefreshControlProps> | undefined;
  testID?: string | undefined;
  style?: StyleProp<ViewStyle>;
}

export function Screen({
  title,
  subtitle,
  children,
  scroll = true,
  headerRight,
  footer,
  refreshControl,
  testID,
  style,
}: ScreenProps) {
  const header =
    title !== undefined || subtitle !== undefined || headerRight !== undefined ? (
      <View style={styles.header}>
        <View style={styles.headerText}>
          {title !== undefined ? (
            <Text style={styles.title} accessibilityRole="header">
              {title}
            </Text>
          ) : null}
          {subtitle !== undefined ? <Text style={styles.subtitle}>{subtitle}</Text> : null}
        </View>
        {headerRight !== undefined ? <View>{headerRight}</View> : null}
      </View>
    ) : null;

  const content = scroll ? (
    <ScrollView
      testID={testID !== undefined ? `${testID}-scroll` : undefined}
      contentContainerStyle={styles.scrollContent}
      keyboardShouldPersistTaps="handled"
      refreshControl={refreshControl}
    >
      {children}
    </ScrollView>
  ) : (
    <View style={styles.fill}>{children}</View>
  );

  return (
    <SafeAreaView style={styles.safe} edges={['top', 'left', 'right']}>
      <KeyboardAvoidingView
        style={styles.fill}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <View testID={testID} style={[styles.fill, style]}>
          {header}
          {content}
          {footer !== undefined ? <View style={styles.footer}>{footer}</View> : null}
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {
    flex: 1,
    backgroundColor: palette.background,
  },
  fill: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.md,
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
    paddingBottom: spacing.sm,
  },
  headerText: {
    flexShrink: 1,
  },
  title: {
    fontSize: typography.title,
    fontWeight: '800',
    color: palette.text,
  },
  subtitle: {
    fontSize: typography.body,
    color: palette.textMuted,
    marginTop: spacing.xs,
  },
  scrollContent: {
    padding: spacing.lg,
    gap: spacing.lg,
  },
  footer: {
    padding: spacing.lg,
    paddingBottom: spacing.xl,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: palette.border,
    backgroundColor: palette.background,
  },
});
