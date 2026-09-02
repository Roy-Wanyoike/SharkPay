/**
 * Home — wallet balances + recent activity. All money rendering goes through
 * MoneyDisplay (BigInt-safe); balances convert via `toBigIntMoney`, which
 * refuses float-unsafe server values instead of displaying rounded money.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { useNavigation } from '@react-navigation/native';
import { RefreshControl, StyleSheet, Text, View } from 'react-native';

import {
  Button,
  Card,
  EmptyState,
  ErrorView,
  MoneyDisplay,
  Screen,
  TransactionRow,
  palette,
  spacing,
  typography,
} from '../components';
import { formatTimestamp } from '../lib/datetime';
import { toBigIntMoney } from '../money/format';
import { useApp } from '../state/AppStore';

const RECENT_COUNT = 5;

export function HomeScreen() {
  const { state, actions, services, dispatch } = useApp();
  const navigation = useNavigation();
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    if (state.sessionPhase !== 'authenticated') {
      return;
    }
    void actions.loadWallets();
    void actions.loadPayments();
    // Initial load only — pull-to-refresh covers the rest.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.all([actions.loadWallets(), actions.loadPayments()]);
    } finally {
      setRefreshing(false);
    }
  }, [actions]);

  const wallets = state.wallets;
  const recentPayments = state.payments.items.slice(0, RECENT_COUNT);
  const busy = wallets.status === 'loading' || wallets.status === 'idle';

  return (
    <Screen
      testID="home-screen"
      title="SharkPay"
      subtitle={services.env.badge === 'prod' ? 'Wallet · production' : 'Wallet · sandbox'}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />
      }
    >
      <View style={styles.walletsHeader}>
        <Text style={styles.sectionTitle}>Balances</Text>
        {wallets.status === 'ready' ? (
          <Text style={styles.walletCount}>
            {wallets.items.length} wallet{wallets.items.length === 1 ? '' : 's'}
          </Text>
        ) : null}
      </View>

      {wallets.status === 'error' ? (
        <ErrorView
          error={wallets.error ?? 'Failed to load wallets.'}
          testID="home-wallets-error"
          onRetry={() => void actions.loadWallets()}
          onDismiss={() => dispatch({ type: 'errors/dismissed', scope: 'wallets' })}
        />
      ) : null}

      {busy ? <Text style={styles.loading}>Loading balances…</Text> : null}

      {wallets.status === 'ready' && wallets.items.length === 0 ? (
        <EmptyState
          title="No wallets yet"
          message="Wallets appear here once the platform provisions them for your account."
          actionLabel="Refresh"
          onAction={() => void actions.loadWallets()}
          testID="home-wallets-empty"
        />
      ) : null}

      {wallets.items.map((wallet) => {
        // toBigIntMoney throws on float-unsafe amounts — that failure mode
        // must surface, not silently round a balance.
        const available = toBigIntMoney(wallet.balances.available);
        const pending = toBigIntMoney(wallet.balances.pending);
        const held = toBigIntMoney(wallet.balances.held);
        return (
          <Card
            key={wallet.id}
            testID={`home-wallet-${wallet.id}`}
            title={`${wallet.currency} wallet`}
            accessory={
              <Text style={wallet.status === 'active' ? styles.walletActive : styles.walletFrozen}>
                {wallet.status}
              </Text>
            }
          >
            <MoneyDisplay
              amountMinor={available.amount_minor}
              exponent={available.exponent}
              currency={available.currency}
              size="lg"
              testID={`home-wallet-${wallet.id}-available`}
            />
            <View style={styles.subBalances}>
              <Text style={styles.subBalance}>
                Pending{' '}
                <Text style={styles.subBalanceValue}>
                  {pending.amount_minor.toString()} minor
                </Text>
              </Text>
              <Text style={styles.subBalance}>
                Held{' '}
                <Text style={styles.subBalanceValue}>{held.amount_minor.toString()} minor</Text>
              </Text>
            </View>
          </Card>
        );
      })}

      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>Recent activity</Text>
        <Button
          label="See all"
          variant="ghost"
          onPress={() => navigation.navigate('Activity')}
          style={styles.seeAll}
        />
      </View>

      {state.payments.status === 'error' ? (
        <ErrorView
          error={state.payments.error ?? 'Failed to load payments.'}
          testID="home-payments-error"
          onRetry={() => void actions.loadPayments()}
          onDismiss={() => dispatch({ type: 'errors/dismissed', scope: 'payments' })}
        />
      ) : null}

      {state.payments.status === 'ready' && recentPayments.length === 0 ? (
        <EmptyState
          title="No activity yet"
          message="Payments you create will show up here."
          testID="home-activity-empty"
        />
      ) : null}

      <View style={styles.activityList}>
        {recentPayments.map((payment) => (
          <TransactionRow
            key={payment.id}
            testID={`home-payment-${payment.id}`}
            title="Payment"
            subtitle={`To ${payment.destination_wallet}`}
            timestamp={formatTimestamp(payment.created_at)}
            amountMinor={BigInt(payment.amount.amount_minor)}
            exponent={payment.amount.exponent}
            currency={payment.amount.currency}
            direction="in"
            state={payment.state}
          />
        ))}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: spacing.xl,
  },
  walletsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sectionTitle: {
    fontSize: typography.label,
    fontWeight: '700',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    color: palette.textMuted,
  },
  walletCount: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  loading: {
    color: palette.textMuted,
    fontSize: typography.body,
    textAlign: 'center',
    padding: spacing.lg,
  },
  walletActive: {
    color: palette.success,
    fontWeight: '700',
    fontSize: typography.caption,
    textTransform: 'capitalize',
  },
  walletFrozen: {
    color: palette.warning,
    fontWeight: '700',
    fontSize: typography.caption,
    textTransform: 'capitalize',
  },
  subBalances: {
    flexDirection: 'row',
    gap: spacing.xl,
    marginTop: spacing.xs,
  },
  subBalance: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  subBalanceValue: {
    fontWeight: '700',
    color: palette.text,
    fontVariant: ['tabular-nums'],
  },
  seeAll: {
    minHeight: 32,
    paddingVertical: 0,
  },
  activityList: {
    backgroundColor: 'transparent',
  },
});
