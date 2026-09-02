/**
 * Splash — shown while the persisted session is being restored from secure
 * storage (and refreshed if expired). Pure presentation; the store drives
 * the phase transition.
 */

import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

import { palette, spacing, typography } from '../components/theme';

export function SplashScreen() {
  return (
    <View testID="splash-screen" style={styles.container}>
      <Text style={styles.brand}>SharkPay</Text>
      <Text style={styles.tagline}>Restoring your session…</Text>
      <ActivityIndicator size="large" color={palette.brand} />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: palette.background,
    gap: spacing.md,
  },
  brand: {
    fontSize: 42,
    fontWeight: '800',
    color: palette.brand,
    letterSpacing: -0.5,
  },
  tagline: {
    fontSize: typography.body,
    color: palette.textMuted,
  },
});
