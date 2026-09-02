/**
 * Activity — merged payments + payouts history, newest first. Payments are
 * credits into a wallet (direction "in"), payouts debits ("out"); amounts
 * render exclusively through MoneyDisplay.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';

import {
  Button,
  EmptyState,
  ErrorView,
  TransactionRow,
  palette,
  spacing,
  typography,
} from '../components';
import type { Payment, Payout } from '../api/types';
import { formatTimestamp } from '../lib/datetime';
import { useApp } from '../state/AppStore';

type Filter = 'all' | 'payments' | 'payouts';

interface ActivityRow {
  id: string;
  kind: 'payment' | 'payout';
  payment?: Payment;
  payout?: Payout;
  createdAt: string;
}

export function ActivityScreen() {
  const { state, actions, dispatch } = useApp();
  const [filter, setFilter] = useState<Filter>('all');
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    void actions.loadPayments();
    void actions.loadPayouts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.all([actions.loadPayments(), actions.loadPayouts()]);
    } finally {
      setRefreshing(false);
    }
  }, [actions]);

  const rows = useMemo<ActivityRow[]>(() => {
    const paymentRows: ActivityRow[] = state.payments.items.map((payment) => ({
      id: payment.id,
      kind: 'payment' as const,
      payment,
      createdAt: payment.created_at,
    }));
    const payoutRows: ActivityRow[] = state.payouts.items.map((payout) => ({
      id: payout.id,
      kind: 'payout' as const,
      payout,
      createdAt: payout.created_at,
    }));
    const merged = [...paymentRows, ...payoutRows];
    merged.sort((left, right) => right.createdAt.localeCompare(left.createdAt));
    if (filter === 'payments') {
      return merged.filter((row) => row.kind === 'payment');
    }
    if (filter === 'payouts') {
      return merged.filter((row) => row.kind === 'payout');
    }
    return merged;
  }, [state.payments.items, state.payouts.items, filter]);

  return (
    <ScrollView
      testID="activity-screen"
      contentContainerStyle={styles.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
    >
      <Text style={styles.title}>Activity</Text>
      <View style={styles.filterRow}>
        {(['all', 'payments', 'payouts'] as const).map((candidate) => (
          <Button
            key={candidate}
            label={candidate}
            variant={filter === candidate ? 'primary' : 'secondary'}
            onPress={() => setFilter(candidate)}
            style={styles.filterChip}
            testID={`activity-filter-${candidate}`}
          />
        ))}
      </View>

      {state.payments.status === 'error' ? (
        <ErrorView
          error={state.payments.error ?? 'Failed to load payments.'}
          testID="activity-payments-error"
          onRetry={() => void actions.loadPayments()}
          onDismiss={() => dispatch({ type: 'errors/dismissed', scope: 'payments' })}
        />
      ) : null}
      {state.payouts.status === 'error' ? (
        <ErrorView
          error={state.payouts.error ?? 'Failed to load payouts.'}
          testID="activity-payouts-error"
          onRetry={() => void actions.loadPayouts()}
          onDismiss={() => dispatch({ type: 'errors/dismissed', scope: 'payouts' })}
        />
      ) : null}

      {rows.length === 0 &&
      state.payments.status !== 'error' &&
      state.payouts.status !== 'error' ? (
        <EmptyState
          title="No activity yet"
          message="Create a payment or payout to see it here."
          testID="activity-empty"
        />
      ) : null}

      {rows.map((row) => {
        if (row.payment !== undefined) {
          const payment = row.payment;
          return (
            <TransactionRow
              key={payment.id}
              testID={`activity-row-${payment.id}`}
              title="Payment"
              subtitle={`To ${payment.destination_wallet}`}
              timestamp={formatTimestamp(payment.created_at)}
              amountMinor={BigInt(payment.amount.amount_minor)}
              exponent={payment.amount.exponent}
              currency={payment.amount.currency}
              direction="in"
              state={payment.state}
            />
          );
        }
        const payout = row.payout;
        if (payout === undefined) {
          return null;
        }
        return (
          <TransactionRow
            key={payout.id}
            testID={`activity-row-${payout.id}`}
            title="Payout"
            subtitle={payout.destination.type === 'mpesa' ? payout.destination.msisdn : payout.destination.type}
            timestamp={formatTimestamp(payout.created_at)}
            amountMinor={BigInt(payout.amount.amount_minor)}
            exponent={payout.amount.exponent}
            currency={payout.amount.currency}
            direction="out"
            state={payout.state}
          />
        );
      })}
    </ScrollView>
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
  filterRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  filterChip: {
    minHeight: 36,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
  },
});
