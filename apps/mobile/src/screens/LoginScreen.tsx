/**
 * Login — the Keycloak sign-in entry point (authorization code + PKCE via
 * expo-auth-session; see src/auth/gateway.ts). A successful login lands in
 * the store as `session/started` and the root navigator swaps trees.
 */

import React, { useCallback, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { Button, ErrorView, palette, spacing, typography } from '../components';
import { AuthCancelledError } from '../auth/types';
import { useApp } from '../state/AppStore';

export function LoginScreen() {
  const { actions, services } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const signIn = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      await actions.login();
      // Tree swap happens via the store (session/started).
    } catch (loginError) {
      if (loginError instanceof AuthCancelledError) {
        setError(new Error('Sign-in was cancelled — tap Sign in to try again.'));
      } else {
        setError(loginError);
      }
    } finally {
      setBusy(false);
    }
  }, [actions]);

  return (
    <View testID="login-screen" style={styles.container}>
      <View style={styles.hero}>
        <Text style={styles.brand}>SharkPay</Text>
        <Text style={styles.tagline}>The wallet that moves money safely.</Text>
      </View>
      <View style={styles.body}>
        {error !== null ? (
          <ErrorView error={error} testID="login-error" onDismiss={() => setError(null)} />
        ) : null}
        <Button
          label="Sign in with Keycloak"
          size="lg"
          loading={busy}
          onPress={() => void signIn()}
          testID="login-button"
        />
        <Text style={styles.hint} numberOfLines={1}>
          {services.env.auth.clientId} · {services.env.auth.url}/realms/{services.env.auth.realm}
        </Text>
        <Text style={styles.envBadge}>
          {services.env.badge === 'prod' ? 'PRODUCTION' : 'SANDBOX'}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: palette.background,
    paddingHorizontal: spacing.xl,
    justifyContent: 'space-between',
    paddingVertical: spacing.xxl,
  },
  hero: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
  },
  brand: {
    fontSize: 48,
    fontWeight: '800',
    color: palette.brand,
    letterSpacing: -0.5,
  },
  tagline: {
    fontSize: typography.body,
    color: palette.textMuted,
    textAlign: 'center',
  },
  body: {
    gap: spacing.lg,
  },
  hint: {
    fontSize: typography.caption,
    color: palette.textMuted,
    textAlign: 'center',
  },
  envBadge: {
    fontSize: typography.caption,
    fontWeight: '700',
    color: palette.warning,
    textAlign: 'center',
    letterSpacing: 1,
  },
});
