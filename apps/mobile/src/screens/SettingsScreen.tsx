/**
 * Settings — session profile, environment coordinates, and sign-out.
 * Doubles as the "what is this build pointed at" inspector (sandbox/prod).
 */

import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';

import { Button, Card, palette, spacing, typography } from '../components';
import { extractRoles, issuerUri, sessionDisplayName } from '../auth/keycloak';
import { formatEpochMs } from '../lib/datetime';
import { useApp } from '../state/AppStore';

const APP_VERSION = '0.1.0';

export function SettingsScreen() {
  const { state, actions, services } = useApp();
  const [busy, setBusy] = useState(false);
  const session = state.session;

  const signOut = async (): Promise<void> => {
    setBusy(true);
    try {
      await actions.logout();
      // Tree swap to Login happens via the store (session/ended).
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScrollView testID="settings-screen" contentContainerStyle={styles.content}>
      <Text style={styles.title}>Settings</Text>

      <Card title="Session">
        {session !== null ? (
          <>
            <Row label="Signed in as">
              <Text style={styles.value}>{sessionDisplayName(session)}</Text>
            </Row>
            {session.claims?.email !== undefined ? (
              <Row label="Email">
                <Text style={styles.value}>{session.claims.email}</Text>
              </Row>
            ) : null}
            {session.claims?.sub !== undefined ? (
              <Row label="Subject">
                <Text style={styles.mono}>{session.claims.sub}</Text>
              </Row>
            ) : null}
            <Row label="Access token expires">
              <Text style={styles.value}>
                {formatEpochMs(session.accessTokenExpiresAtMs)}
              </Text>
            </Row>
            <Row label="Realm roles">
              <Text style={styles.value}>{extractRoles(session).join(', ') || '—'}</Text>
            </Row>
          </>
        ) : (
          <Text style={styles.value}>No active session.</Text>
        )}
      </Card>

      <Card title="Environment">
        <Row label="Mode">
          <Text
            style={[styles.value, services.env.badge === 'prod' ? styles.prod : styles.sandbox]}
          >
            {services.env.badge.toUpperCase()}
          </Text>
        </Row>
        <Row label="API base">
          <Text style={styles.mono}>{services.env.apiBaseUrl}</Text>
        </Row>
        <Row label="OIDC issuer">
          <Text style={styles.mono}>{issuerUri(services.env.auth)}</Text>
        </Row>
        <Row label="Client id">
          <Text style={styles.mono}>{services.env.auth.clientId}</Text>
        </Row>
        <Row label="Redirect URI">
          <Text style={styles.mono}>{services.env.auth.redirectUri}</Text>
        </Row>
      </Card>

      <Card title="About">
        <Row label="Version">
          <Text style={styles.value}>{APP_VERSION}</Text>
        </Row>
        <Row label="Money handling">
          <Text style={styles.value}>integer minor units · bigint</Text>
        </Row>
        <Text style={styles.note}>
          Amounts are parsed from strings and formatted with bigint arithmetic only — no floating
          point ever touches money (ADR 001 §4).
        </Text>
      </Card>

      <Button
        label="Sign out"
        variant="destructive"
        loading={busy}
        onPress={() => void signOut()}
        testID="settings-signout"
      />
    </ScrollView>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <View style={styles.rowValue}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  content: {
    padding: spacing.lg,
    gap: spacing.lg,
  },
  title: {
    fontSize: typography.title,
    fontWeight: '800',
    color: palette.text,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  rowLabel: {
    fontSize: typography.label,
    color: palette.textMuted,
  },
  rowValue: {
    alignItems: 'flex-end',
    flexShrink: 1,
  },
  value: {
    fontSize: typography.body,
    color: palette.text,
    fontWeight: '600',
    textAlign: 'right',
  },
  mono: {
    fontSize: typography.label,
    fontFamily: 'monospace',
    color: palette.text,
    textAlign: 'right',
  },
  sandbox: {
    color: palette.success,
  },
  prod: {
    color: palette.danger,
  },
  note: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
});
