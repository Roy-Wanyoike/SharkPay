/**
 * Payouts — on-device payout tracking + the payout creation flow
 * (amount keypad → destination → confirm with fee display → idempotent
 * submit). Money-safety identical to the Send flow: string keypad →
 * `parseAmountToMinor` (bigint, no floats), one Idempotency-Key per logical
 * intent, `minorToWireNumber` refusing > 2^53−1.
 *
 * Contract note: payouts.yaml has NO list endpoint at V1, so the list shows
 * payouts created on this device (store) and refreshes each by id
 * (`loadPayouts`); the wallet statement is the authoritative history.
 */

import React, { useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  AmountKeypad,
  Button,
  Card,
  EmptyState,
  ErrorView,
  MoneyDisplay,
  StatusBadge,
  TransactionRow,
  palette,
  spacing,
  typography,
} from '../components';
import type {
  BankDestination,
  Currency,
  MpesaDestination,
  OnChainDestination,
  Payout,
  PayoutDestination,
  PayoutRail,
} from '../api/types';
import { IdempotencyConflictError } from '../api/errors';
import { generateIdempotencyKey } from '../api/idempotency';
import { estimatePayoutFee } from '../money/fee';
import { minorToWireNumber, sumMoney } from '../money/format';
import { parseAmountToMinor } from '../money/parse';
import { formatTimestamp } from '../lib/datetime';
import { useApp } from '../state/AppStore';

type Step = 'list' | 'amount' | 'destination' | 'confirm' | 'receipt';
type DestinationType = PayoutDestination['type'];

export function PayoutsScreen() {
  const { state, actions } = useApp();

  const [step, setStep] = useState<Step>('list');
  const [amountInput, setAmountInput] = useState('');
  const [currency, setCurrency] = useState<Currency>(
    state.wallets.items[0]?.currency ?? 'KES',
  );
  const [sourceWallet, setSourceWallet] = useState('');
  const [destinationType, setDestinationType] = useState<DestinationType>('mpesa');
  const [msisdn, setMsisdn] = useState('');
  const [bankCode, setBankCode] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [accountName, setAccountName] = useState('');
  const [network, setNetwork] = useState<OnChainDestination['network']>('base');
  const [address, setAddress] = useState('');
  const [intentKey, setIntentKey] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [receipt, setReceipt] = useState<Payout | null>(null);

  const exponent = currency === 'USDC' || currency === 'USDT' ? 6 : 2;
  const railForType: PayoutRail =
    destinationType === 'mpesa' ? 'mpesa' : destinationType === 'bank' ? 'bank' : 'on_chain';

  const sourceWallets = useMemo(
    () => state.wallets.items.filter((wallet) => wallet.currency === currency),
    [state.wallets.items, currency],
  );

  const parsedAmount = useMemo<{ amountMinor: bigint } | { error: string }>(() => {
    if (amountInput.length === 0) {
      return { error: 'Enter an amount' };
    }
    try {
      return { amountMinor: parseAmountToMinor(amountInput, exponent) };
    } catch (error) {
      return { error: error instanceof Error ? error.message : 'Invalid amount' };
    }
  }, [amountInput, exponent]);

  const feeEstimate = useMemo(() => {
    if (!('amountMinor' in parsedAmount)) {
      return null;
    }
    return estimatePayoutFee(parsedAmount.amountMinor, railForType);
  }, [parsedAmount, railForType]);

  const destination: PayoutDestination | null = useMemo(() => {
    if (destinationType === 'mpesa') {
      return /^\+?[0-9]{10,15}$/.test(msisdn.trim())
        ? { type: 'mpesa', msisdn: msisdn.trim() }
        : null;
    }
    if (destinationType === 'bank') {
      return bankCode.trim().length >= 2 && accountNumber.trim().length >= 4
        ? {
            type: 'bank',
            bank_code: bankCode.trim(),
            account_number: accountNumber.trim(),
            ...(accountName.trim().length > 0 ? { account_name: accountName.trim() } : {}),
          }
        : null;
    }
    return /^0x[0-9a-fA-F]{40}$/.test(address.trim())
      ? { type: 'on_chain', network, address: address.trim() }
      : null;
  }, [destinationType, msisdn, bankCode, accountNumber, accountName, network, address]);

  const submit = async (): Promise<void> => {
    if (!('amountMinor' in parsedAmount) || destination === null || intentKey === null) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const amountWire = minorToWireNumber(parsedAmount.amountMinor);
      const payout = await actions.submitPayout(
        {
          source_wallet: sourceWallet.trim(),
          amount_minor: amountWire,
          currency,
          destination,
          rail: railForType,
        },
        intentKey,
      );
      setReceipt(payout);
      setStep('receipt');
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const resetFlow = (): void => {
    setStep('list');
    setAmountInput('');
    setIntentKey(null);
    setSubmitError(null);
    setReceipt(null);
    setMsisdn('');
    setBankCode('');
    setAccountNumber('');
    setAccountName('');
    setAddress('');
  };

  const friendlyError = useMemo<unknown>(() => {
    if (submitError instanceof IdempotencyConflictError) {
      return new Error(
        'This confirmation was already used with different details. Start a new payout instead of retrying this one.',
      );
    }
    return submitError;
  }, [submitError]);

  if (step === 'list') {
    return (
      <ScrollView
        testID="payouts-screen"
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.title}>Payouts</Text>
        <Text style={styles.subtitle}>
          Withdraw from a wallet to M-Pesa, a bank account or an on-chain address.
        </Text>
        {state.payouts.items.length === 0 ? (
          <EmptyState
            title="No payouts yet"
            message="Payouts created on this device appear here and are refreshed by id."
            actionLabel="New payout"
            onAction={() => setStep('amount')}
            testID="payouts-empty"
          />
        ) : (
          <View>
            {state.payouts.items.map((payout) => (
              <TransactionRow
                key={payout.id}
                testID={`payout-row-${payout.id}`}
                title="Payout"
                subtitle={payoutDestinationLabel(payout.destination)}
                timestamp={formatTimestamp(payout.created_at)}
                amountMinor={BigInt(payout.amount.amount_minor)}
                exponent={payout.amount.exponent}
                currency={payout.amount.currency}
                direction="out"
                state={payout.state}
              />
            ))}
          </View>
        )}
        <Button
          label="New payout"
          onPress={() => {
            if (sourceWallets.length > 0) {
              setSourceWallet(sourceWallets[0]?.id ?? '');
            }
            setStep('amount');
          }}
          testID="payouts-new"
        />
        <Button
          label="Refresh by id"
          variant="ghost"
          onPress={() => void actions.loadPayouts()}
          testID="payouts-refresh"
        />
      </ScrollView>
    );
  }

  if (step === 'amount') {
    return (
      <ScrollView testID="payouts-amount-step" contentContainerStyle={styles.content}>
        <Text style={styles.title}>How much?</Text>
        <View style={styles.chipRow}>
          {sourceWallets.length > 0
            ? sourceWallets.map((wallet) => (
                <Button
                  key={wallet.id}
                  label={wallet.currency}
                  variant={wallet.currency === currency ? 'primary' : 'secondary'}
                  onPress={() => {
                    setCurrency(wallet.currency);
                    setSourceWallet(wallet.id);
                  }}
                  style={styles.chip}
                  testID={`payouts-currency-${wallet.currency}`}
                />
              ))
            : (
              <Text style={styles.hint}>
                No {currency} wallet loaded — the source wallet id will be entered on confirm.
              </Text>
            )}
        </View>
        <AmountKeypad
          value={amountInput}
          onChange={setAmountInput}
          exponent={exponent}
          currency={currency}
          testID="payouts-keypad"
        />
        {'error' in parsedAmount ? (
          <Text style={styles.error}>{parsedAmount.error}</Text>
        ) : null}
        <View style={styles.row}>
          <Button label="Cancel" variant="ghost" onPress={resetFlow} style={styles.grow} />
          <Button
            label="Continue"
            disabled={!('amountMinor' in parsedAmount)}
            onPress={() => setStep('destination')}
            style={styles.grow}
            testID="payouts-continue-destination"
          />
        </View>
      </ScrollView>
    );
  }

  if (step === 'destination') {
    return (
      <ScrollView testID="payouts-destination-step" contentContainerStyle={styles.content}>
        <Text style={styles.title}>Destination</Text>
        <View style={styles.chipRow}>
          {(['mpesa', 'bank', 'on_chain'] as const).map((type) => (
            <Button
              key={type}
              label={type}
              variant={destinationType === type ? 'primary' : 'secondary'}
              onPress={() => setDestinationType(type)}
              style={styles.chip}
              testID={`payouts-destination-type-${type}`}
            />
          ))}
        </View>

        {destinationType === 'mpesa' ? (
          <>
            <Text style={styles.fieldLabel}>M-Pesa number</Text>
            <TextInput
              testID="payouts-msisdn"
              value={msisdn}
              onChangeText={setMsisdn}
              placeholder="+254712345678"
              keyboardType="phone-pad"
              style={styles.input}
            />
          </>
        ) : null}

        {destinationType === 'bank' ? (
          <>
            <Text style={styles.fieldLabel}>Bank code</Text>
            <TextInput
              testID="payouts-bank-code"
              value={bankCode}
              onChangeText={setBankCode}
              placeholder="e.g. 00100"
              style={styles.input}
            />
            <Text style={styles.fieldLabel}>Account number</Text>
            <TextInput
              testID="payouts-account-number"
              value={accountNumber}
              onChangeText={setAccountNumber}
              style={styles.input}
            />
            <Text style={styles.fieldLabel}>Account name (optional)</Text>
            <TextInput
              testID="payouts-account-name"
              value={accountName}
              onChangeText={setAccountName}
              style={styles.input}
            />
          </>
        ) : null}

        {destinationType === 'on_chain' ? (
          <>
            <Text style={styles.fieldLabel}>Network</Text>
            <View style={styles.chipRow}>
              {(['base', 'ethereum', 'polygon'] as const).map((candidate) => (
                <Button
                  key={candidate}
                  label={candidate}
                  variant={network === candidate ? 'primary' : 'secondary'}
                  onPress={() => setNetwork(candidate)}
                  style={styles.chip}
                  testID={`payouts-network-${candidate}`}
                />
              ))}
            </View>
            <Text style={styles.fieldLabel}>Address</Text>
            <TextInput
              testID="payouts-address"
              value={address}
              onChangeText={setAddress}
              placeholder="0x…"
              autoCapitalize="none"
              autoCorrect={false}
              style={styles.input}
            />
          </>
        ) : null}

        <View style={styles.row}>
          <Button
            label="Back"
            variant="ghost"
            onPress={() => setStep('amount')}
            style={styles.grow}
          />
          <Button
            label="Continue"
            disabled={destination === null}
            onPress={() => {
              setIntentKey(generateIdempotencyKey());
              setStep('confirm');
            }}
            style={styles.grow}
            testID="payouts-continue-confirm"
          />
        </View>
      </ScrollView>
    );
  }

  if (step === 'confirm') {
    const amountMinor = 'amountMinor' in parsedAmount ? parsedAmount.amountMinor : null;
    const total =
      amountMinor !== null && feeEstimate !== null
        ? sumMoney([
            { amount_minor: amountMinor, currency, exponent },
            { amount_minor: feeEstimate.feeMinor, currency, exponent },
          ])
        : null;
    return (
      <ScrollView testID="payouts-confirm-step" contentContainerStyle={styles.content}>
        <Text style={styles.title}>Confirm payout</Text>
        <Card title="Details">
          <DetailRow label="Amount">
            {amountMinor !== null ? (
              <MoneyDisplay
                amountMinor={amountMinor}
                exponent={exponent}
                currency={currency}
                testID="payouts-confirm-amount"
              />
            ) : null}
          </DetailRow>
          <DetailRow label="Source wallet">
            <Text style={styles.mono}>{sourceWallet || 'unset'}</Text>
          </DetailRow>
          <DetailRow label="Rail">
            <Text style={styles.value}>{railForType}</Text>
          </DetailRow>
          <DetailRow label="Destination">
            <Text style={styles.mono}>
              {destination !== null ? payoutDestinationLabel(destination) : 'incomplete'}
            </Text>
          </DetailRow>
          {feeEstimate !== null ? (
            <>
              <DetailRow label="Fee (est.)">
                <MoneyDisplay
                  amountMinor={feeEstimate.feeMinor}
                  exponent={exponent}
                  currency={currency}
                  size="sm"
                  testID="payouts-confirm-fee"
                />
              </DetailRow>
              <DetailRow label="Non-refundable if returned">
                <MoneyDisplay
                  amountMinor={feeEstimate.nonRefundableMinor}
                  exponent={exponent}
                  currency={currency}
                  size="sm"
                  tone="muted"
                  testID="payouts-confirm-nonrefundable"
                />
              </DetailRow>
            </>
          ) : null}
          {total !== null ? (
            <DetailRow label="Total (est.)">
              <MoneyDisplay
                amountMinor={total.amount_minor}
                exponent={total.exponent}
                currency={currency}
                size="sm"
                tone="muted"
                testID="payouts-confirm-total"
              />
            </DetailRow>
          ) : null}
        </Card>
        <Text style={styles.hint}>
          Estimate from the V1 payout schedule; the fee on the receipt is authoritative. Funds are
          held when the payout is created.
        </Text>
        {friendlyError !== null ? (
          <ErrorView error={friendlyError} testID="payouts-submit-error" />
        ) : null}
        <View style={styles.row}>
          <Button
            label="Back"
            variant="ghost"
            disabled={submitting}
            onPress={() => {
              setIntentKey(null);
              setStep('destination');
            }}
            style={styles.grow}
          />
          <Button
            label="Confirm & send"
            loading={submitting}
            disabled={destination === null || amountMinor === null || sourceWallet.trim() === ''}
            onPress={() => void submit()}
            style={styles.grow}
            testID="payouts-submit"
          />
        </View>
      </ScrollView>
    );
  }

  // receipt
  const payout = receipt;
  if (payout === null) {
    return null;
  }
  const amount = { ...payout.amount, amount_minor: BigInt(payout.amount.amount_minor) };
  const fee = { ...payout.fee, amount_minor: BigInt(payout.fee.amount_minor) };
  const total =
    amount.currency === fee.currency && amount.exponent === fee.exponent
      ? sumMoney([amount, fee])
      : null;
  return (
    <ScrollView testID="payouts-receipt-step" contentContainerStyle={styles.content}>
      <Text style={styles.title}>Payout created</Text>
      <Card
        title="Receipt"
        accessory={<StatusBadge state={payout.state} testID="payouts-receipt-status" />}
      >
        <DetailRow label="Payout">
          <Text style={styles.mono}>{payout.id}</Text>
        </DetailRow>
        <DetailRow label="Amount">
          <MoneyDisplay
            amountMinor={amount.amount_minor}
            exponent={amount.exponent}
            currency={amount.currency}
            testID="payouts-receipt-amount"
          />
        </DetailRow>
        <DetailRow label="Fee (final)">
          <MoneyDisplay
            amountMinor={fee.amount_minor}
            exponent={fee.exponent}
            currency={fee.currency}
            size="sm"
            testID="payouts-receipt-fee"
          />
        </DetailRow>
        {total !== null ? (
          <DetailRow label="Total">
            <MoneyDisplay
              amountMinor={total.amount_minor}
              exponent={total.exponent}
              currency={total.currency}
              size="sm"
              tone="muted"
              testID="payouts-receipt-total"
            />
          </DetailRow>
        ) : null}
        <DetailRow label="Destination">
          <Text style={styles.mono}>{payoutDestinationLabel(payout.destination)}</Text>
        </DetailRow>
      </Card>
      <Button label="Done" onPress={resetFlow} testID="payouts-receipt-done" />
    </ScrollView>
  );
}

function DetailRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.detailRow}>
      <Text style={styles.rowLabel}>{label}</Text>
      <View style={styles.rowValue}>{children}</View>
    </View>
  );
}

function payoutDestinationLabel(destination: PayoutDestination): string {
  switch (destination.type) {
    case 'mpesa':
      return `M-Pesa ${destination.msisdn}`;
    case 'bank':
      return `Bank ${destination.bank_code} ··${destination.account_number.slice(-4)}`;
    case 'on_chain':
      return `${destination.network} ${destination.address.slice(0, 10)}…`;
  }
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
  subtitle: {
    fontSize: typography.body,
    color: palette.textMuted,
  },
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  chip: {
    minHeight: 38,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
  },
  fieldLabel: {
    fontSize: typography.label,
    fontWeight: '700',
    color: palette.textMuted,
    textTransform: 'uppercase',
    letterSpacing: 0.4,
  },
  input: {
    backgroundColor: palette.surface,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: 10,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    fontSize: typography.body,
    color: palette.text,
  },
  hint: {
    fontSize: typography.caption,
    color: palette.textMuted,
  },
  error: {
    color: palette.danger,
    fontSize: typography.caption,
    fontWeight: '600',
  },
  row: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  grow: {
    flex: 1,
  },
  detailRow: {
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
  },
  mono: {
    fontSize: typography.label,
    fontFamily: 'monospace',
    color: palette.text,
  },
});
