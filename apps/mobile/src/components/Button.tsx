/**
 * Button — the design system's primary interactive element.
 *
 * Variants: primary (brand), secondary (outline), destructive, ghost.
 * `loading` disables interaction and swaps the label for a spinner, so an
 * in-flight money mutation can never be double-tapped.
 */

import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { palette, radius, spacing, typography } from './theme';

export interface ButtonProps {
  label: string;
  onPress?: () => void;
  variant?: 'primary' | 'secondary' | 'destructive' | 'ghost';
  disabled?: boolean;
  /** Shows a spinner and blocks interaction (submit-in-flight state). */
  loading?: boolean;
  size?: 'md' | 'lg';
  testID?: string | undefined;
  style?: StyleProp<ViewStyle>;
}

const VARIANT_STYLES = {
  primary: {
    container: { backgroundColor: palette.brand },
    label: { color: palette.textOnBrand },
    outline: {} as StyleProp<ViewStyle>,
    pressed: { backgroundColor: palette.brandPressed },
  },
  secondary: {
    container: { backgroundColor: 'transparent' },
    label: { color: palette.brand },
    outline: { borderWidth: 1.5, borderColor: palette.brand } as StyleProp<ViewStyle>,
    pressed: { backgroundColor: palette.brandSoft },
  },
  destructive: {
    container: { backgroundColor: palette.danger },
    label: { color: palette.textOnBrand },
    outline: {} as StyleProp<ViewStyle>,
    pressed: { backgroundColor: palette.dangerPressed },
  },
  ghost: {
    container: { backgroundColor: 'transparent' },
    label: { color: palette.brand },
    outline: {} as StyleProp<ViewStyle>,
    pressed: { backgroundColor: palette.brandSoft },
  },
} as const;

export function Button({
  label,
  onPress,
  variant = 'primary',
  disabled = false,
  loading = false,
  size = 'md',
  testID,
  style,
}: ButtonProps) {
  const variantStyle = VARIANT_STYLES[variant];
  const inactive = disabled || loading;
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: inactive, busy: loading }}
      onPress={inactive ? undefined : onPress}
      style={({ pressed }) => [
        styles.base,
        size === 'lg' ? styles.large : styles.medium,
        variantStyle.container,
        variantStyle.outline,
        inactive && styles.disabled,
        pressed && !inactive ? variantStyle.pressed : null,
        style,
      ]}
    >
      <View style={styles.content}>
        {loading ? (
          <ActivityIndicator
            size="small"
            color={variantStyle.label.color}
            testID={testID !== undefined ? `${testID}-spinner` : undefined}
          />
        ) : null}
        <Text
          style={[
            styles.label,
            { color: variantStyle.label.color },
            inactive && styles.labelMuted,
            loading && styles.labelHidden,
          ]}
        >
          {label}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  medium: {
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.lg,
    minHeight: 46,
  },
  large: {
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.xl,
    minHeight: 56,
  },
  content: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  label: {
    fontSize: typography.body,
    fontWeight: '600',
    textAlign: 'center',
  },
  labelHidden: {
    // Label stays in the a11y tree while the spinner shows.
    opacity: 0,
  },
  labelMuted: {
    opacity: 0.6,
  },
  disabled: {
    opacity: 0.55,
  },
});
