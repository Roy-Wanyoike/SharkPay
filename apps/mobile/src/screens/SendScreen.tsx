/**
 * Send — the payment flow (mission contract: amount keypad → destination →
 * confirm with fee display → idempotent submit).
 *
 * Money-safety invariants pinned by this screen:
 * - The keypad edits a STRING; minor units come from `parseAmountToMinor`
 *   (never a float) and travel as bigint until the final wire conversion
 *   (`minorToWireNumber`, which REFUSES amounts beyond ±2^53−1).
 * - The Idempotency-Key is minted ONCE per logical intent (entering
 *   confirm) and REUSED across every retry — a retried POST can never
 *   create a second payment. Editing details (going back) mints a new key.
 * - The confirm step shows a fee ESTIMATE mirroring the server's V1 fee
 *   schedule (src/money/fee.ts); the receipt shows the AUTHORITATIVE fee
 *   from the created intent.
 */

import React, { useEffect, useMemo, useState } from 'react';
import { useNavigation } from '@react-navigation/native';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

import {
  AmountKeypad,
  Button,
  Card,
  ErrorView,
  MoneyDisplay,
  StatusBadge,
  palette,
  spacing,
  typography,
} from '../components';
import { CURRENCIES, type Currency, type Payment, type Rail } from '../api/types';
import { generateIdempotencyKey } from '../api/idempotency';
import { IdempotencyConflictError } from '../api/errors';
import {
  CANONICAL_RAIL_ORDER,
  defaultPaymentRailFor,
  estimatePaymentFee,
  paymentFeePolicy,
} from '../money/fee';
import { minorToWireNumber, sumMoney } from '../money/format';
import { parseAmountToMinor } from '../money/parse';
import { useApp } from '../state/AppStore';

type Step = 'amount' | 'destination' | 'confirm' | 'receipt';

export function SendScreen() {
  const { state, actions } = useApp();
  const navigation = useNavigation();

  const [step, setStep] = useState<Step>('amount');
  const [amountInput, setAmountInput] = useState('');
  const [currency, setCurrency] = useState<Currency>(
    state.wallets.items[0]?.currency ?? 'KES',
  );
  const [destinationWallet, setDestinationWallet] = useState('');
  const [rail, setRail] = useState<Rail>(
    defaultPaymentRailFor(state.wallets.items[0]?.currency ?? 'KES') ?? 'honeycoin',
  );
  const [intentKey, setIntentKey] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<unknown>(null);
  const [receipt, setReceipt] = useState<Payment | null>(null);

  const exponent = useMemo(() => {
    switch (currency) {
      case 'USDC':
      case 'USDT':
        return 6;
      default:
        return 2;
    }
  }, [currency]);

  const walletCurrencies = useMemo(() => {
    const unique = new Set<Currency>(state.wallets.items.map((wallet) => wallet.currency));
    return unique.size > 0 ? [...unique] : [...CURRENCIES];
  }, [state.wallets.items]);

  const servedRails = useMemo(
    () => CANONICAL_RAIL_ORDER.filter((candidate) => paymentFeePolicy(candidate, currency) !== null),
    [currency],
  );

  // Keep the rail hint coherent with the chosen currency (deterministic
  // default = first rail in canonical order serving the currency).
  useEffect(() => {
    if (!servedRails.includes(rail)) {
      setRail(defaultPaymentRailFor(currency) ?? servedRails[0] ?? 'honeycoin');
    }
  }, [currency, rail, servedRails]);

  // Amount parse: the ONLY path from keypad string to minor units.
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
    return estimatePaymentFee(parsedAmount.amountMinor, rail, currency);
  }, [parsedAmount, rail, currency]);

  const destinationValid = /^wal_[0-9A-Za-z]{6,}$/.test(destinationWallet.trim());

  const submit = async (): Promise<void> => {
    if (!('amountMinor' in parsedAmount) || intentKey === null) {
      return;
    }
    setSubmitting(true);
    setSubmitError(null);
    try {
      const amountMinor = parsedAmount.amountMinor;
      // Beyond ±2^53−1 minor units the JSON wire format cannot carry the
      // amount exactly — refuse rather than corrupt it.
      const amountWire = minorToWireNumber(amountMinor);
      const payment = await actions.submitPayment(
        {
          amount_minor: amountWire,
          currency,
          destination_wallet: destinationWallet.trim(),
          rail,
        },
        intentKey,
      );
      setReceipt(payment);
      setStep('receipt');
    } catch (error) {
      setSubmitError(error);
    } finally {
      setSubmitting(false);
    }
  };

  const goToConfirm = (): void => {
    // Mint the intent key exactly here: one key per logical payment intent.
    setIntentKey(generateIdempotencyKey());
    setSubmitError(null);
    setStep('confirm');
  };

  const goBackToAmount = (): void => {
    // Editing details = a NEW logical intent ⇒ the old key must die.
    setIntentKey(null);
    setStep('amount');
  };

  const sendAnother = (): void => {
    setReceipt(null);
    setSubmitError(null);
    setIntentKey(null);
    setAmountInput('');
    setDestinationWallet('');
    setStep('amount');
  };

  const friendlyError = useMemo<unknown>(() => {
    if (submitError instanceof IdempotencyConflictError) {
      return new Error(
        'This confirmation was already used with different details. Start a new payment (Send another) instead of retrying this one.',
      );
    }
    return submitError;
  }, [submitError]);

  return (
    <View testID="send-screen" style={styles.flex}>
      {step === 'amount' ? (
        <AmountStep
          currency={currency}
          currencies={walletCurrencies}
          onCurrency={setCurrency}
          value={amountInput}
          onChange={setAmountInput}
          exponent={exponent}
          parseError={'error' in parsedAmount ? parsedAmount.error : null}
          onCancel={() => navigation.goBack()}
          onContinue={() => setStep('destination')}
          continueEnabled={'amountMinor' in parsedAmount}
        />
      ) : null}

      {step === 'destination' ? (
        <DestinationStep
          currency={currency}
          destination={destinationWallet}
          onChangeDestination={setDestinationWallet}
          rail={rail}
          servedRails={servedRails}
          onRail={setRail}
          onBack={() => setStep('amount')}
          onContinue={goToConfirm}
          continueEnabled={destinationValid}
        />
      ) : null}

      {step === 'confirm' ? (
        <ConfirmStep
          currency={currency}
          exponent={exponent}
          amountMinor={'amountMinor' in parsedAmount ? parsedAmount.amountMinor : null}
          destinationWallet={destinationWallet.trim()}
          rail={rail}
          feeEstimate={feeEstimate}
          submitting={submitting}
          error={friendlyError}
          onBack={() => {
            setIntentKey(null);
            setStep('destination');
          }}
          onEditAmount={goBackToAmount}
          onSubmit={() => void submit()}
        />
      ) : null}

      {step === 'receipt' && receipt !== null ? (
        <ReceiptStep
          payment={receipt}
          onDone={() => navigation.goBack()}
          onSendAnother={sendAnother}
        />
      ) : null}
    </View>
  );
}

// ─── Step components (same file: one flow, one seam) ─────────────────────────

function AmountStep({
  currency,
  currencies,
  onCurrency,
  value,
  onChange,
  exponent,
  parseError,
  onCancel,
  onContinue,
  continueEnabled,
}: {
  currency: Currency;
  currencies: readonly Currency[];
  onCurrency(currency: Currency): void;
  value: string;
  onChange(next: string): void;
  exponent: number;
  parseError: string | null;
  onCancel(): void;
  onContinue(): void;
  continueEnabled: boolean;
}) {
  return (
    <ScrollView
      testID="send-amount-step"
      contentContainerStyle={styles.stepContent}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.stepTitle}>How much?</Text>
      <View style={styles.currencyRow}>
        {currencies.map((candidate) => (
          <Button
            key={candidate}
            label={candidate}
            variant={candidate === currency ? 'primary' : 'secondary'}
            onPress={() => onCurrency(candidate)}
            style={styles.currencyChip}
            testID={`send-currency-${candidate}`}
          />
        ))}
      </View>
      <AmountKeypad
        value={value}
        onChange={onChange}
        exponent={exponent}
        currency={currency}
        testID="send-keypad"
      />
      {parseError !== null ? <Text style={styles.parseError}>{parseError}</Text> : null}
      <View style={styles.row}>
        <Button label="Cancel" variant="ghost" onPress={onCancel} style={styles.grow} />
        <Button
          label="Continue"
          onPress={onContinue}
          disabled={!continueEnabled}
          style={styles.grow}
          testID="send-continue-destination"
        />
      </View>
    </ScrollView>
  );
}

function DestinationStep({
  currency,
  destination,
  onChangeDestination,
  rail,
  servedRails,
  onRail,
  onBack,
  onContinue,
  continueEnabled,
}: {
  currency: Currency;
  destination: string;
  onChangeDestination(next: string): void;
  rail: Rail;
  servedRails: readonly Rail[];
  onRail(rail: Rail): void;
  onBack(): void;
  onContinue(): void;
  continueEnabled: boolean;
}) {
  return (
    <ScrollView
      testID="send-destination-step"
      contentContainerStyle={styles.stepContent}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.stepTitle}>Where is it going?</Text>
      <Text style={styles.fieldLabel}>Destination wallet ({currency})</Text>
      <TextInput
        testID="send-destination-input"
        value={destination}
        onChangeText={onChangeDestination}
        placeholder="wal_…"
        autoCapitalize="none"
        autoCorrect={false}
        style={styles.input}
        accessibilityLabel="Destination wallet id"
      />
      <Text style={styles.hint}>The wallet that will receive the collected {currency}.</Text>

      {servedRails.length > 1 ? (
        <>
          <Text style={styles.fieldLabel}>Rail hint</Text>
          <View style={styles.currencyRow}>
            {servedRails.map((candidate) => (
              <Button
                key={candidate}
                label={candidate}
                variant={candidate === rail ? 'primary' : 'secondary'}
                onPress={() => onRail(candidate)}
                style={styles.currencyChip}
                testID={`send-rail-${candidate}`}
              />
            ))}
          </View>
          <Text style={styles.hint}>The router makes the final provider choice.</Text>
        </>
      ) : null}

      <View style={styles.row}>
        <Button label="Back" variant="ghost" onPress={onBack} style={styles.grow} />
        <Button
          label="Continue"
          onPress={onContinue}
          disabled={!continueEnabled}
          style={styles.grow}
          testID="send-continue-confirm"
        />
      </View>
    </ScrollView>
  );
}

function ConfirmStep({
  currency,
  exponent,
  amountMinor,
  destinationWallet,
  rail,
  feeEstimate,
  submitting,
  error,
  onBack,
  onEditAmount,
  onSubmit,
}: {
  currency: Currency;
  exponent: number;
  amountMinor: bigint | null;
  destinationWallet: string;
  rail: Rail;
  feeEstimate: bigint | null;
  submitting: boolean;
  error: unknown;
  onBack(): void;
  onEditAmount(): void;
  onSubmit(): void;
}) {
  const total =
    amountMinor !== null && feeEstimate !== null
      ? sumMoney([
          { amount_minor: amountMinor, currency, exponent },
          { amount_minor: feeEstimate, currency, exponent },
        ])
      : null;

  return (
    <ScrollView
      testID="send-confirm-step"
      contentContainerStyle={styles.stepContent}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.stepTitle}>Confirm payment</Text>
      <Card title="Details">
        <Row label="Amount">
          {amountMinor !== null ? (
            <MoneyDisplay
              amountMinor={amountMinor}
              exponent={exponent}
              currency={currency}
              size="md"
              testID="send-confirm-amount"
            />
          ) : null}
        </Row>
        <Row label="Destination">
          <Text style={styles.mono}>{destinationWallet}</Text>
        </Row>
        <Row label="Rail">
          <Text style={styles.value}>{rail}</Text>
        </Row>
        <Row label="Fee (est.)">
          {feeEstimate !== null ? (
            <MoneyDisplay
              amountMinor={feeEstimate}
              exponent={exponent}
              currency={currency}
              size="sm"
              testID="send-confirm-fee"
            />
          ) : (
            <Text style={styles.parseError}>No rail schedule for {currency} on {rail}</Text>
          )}
        </Row>
        {total !== null ? (
          <Row label="Total (est.)">
            <MoneyDisplay
              amountMinor={total.amount_minor}
              exponent={total.exponent}
              currency={currency}
              size="sm"
              tone="muted"
              testID="send-confirm-total"
            />
          </Row>
        ) : null}
      </Card>
      <Text style={styles.hint}>
        Fee is estimated from the V1 schedule. The authoritative fee is set by the server when
        the payment is created and shown on the receipt.
      </Text>
      {error !== null ? <ErrorView error={error} testID="send-submit-error" /> : null}
      <View style={styles.row}>
        <Button
          label="Back"
          variant="ghost"
          onPress={onBack}
          disabled={submitting}
          style={styles.grow}
        />
        <Button
          label="Confirm & send"
          loading={submitting}
          disabled={feeEstimate === null || amountMinor === null}
          onPress={onSubmit}
          style={styles.grow}
          testID="send-submit"
        />
      </View>
      <Button label="Edit amount" variant="ghost" onPress={onEditAmount} disabled={submitting} />
    </ScrollView>
  );
}

function ReceiptStep({
  payment,
  onDone,
  onSendAnother,
}: {
  payment: Payment;
  onDone(): void;
  onSendAnother(): void;
}) {
  const amount = { ...payment.amount, amount_minor: BigInt(payment.amount.amount_minor) };
  const fee = { ...payment.fee, amount_minor: BigInt(payment.fee.amount_minor) };
  const total =
    amount.currency === fee.currency && amount.exponent === fee.exponent
      ? sumMoney([amount, fee])
      : null;
  return (
    <ScrollView
      testID="send-receipt-step"
      contentContainerStyle={styles.stepContent}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={styles.stepTitle}>Payment created</Text>
      <Card title="Receipt" accessory={<StatusBadge state={payment.state} testID="send-receipt-status" />}>
        <Row label="Payment">
          <Text style={styles.mono}>{payment.id}</Text>
        </Row>
        <Row label="Amount">
          <MoneyDisplay
            amountMinor={amount.amount_minor}
            exponent={amount.exponent}
            currency={amount.currency}
            size="md"
            testID="send-receipt-amount"
          />
        </Row>
        <Row label="Fee (final)">
          <MoneyDisplay
            amountMinor={fee.amount_minor}
            exponent={fee.exponent}
            currency={fee.currency}
            size="sm"
            testID="send-receipt-fee"
          />
        </Row>
        {total !== null ? (
          <Row label="Total">
            <MoneyDisplay
              amountMinor={total.amount_minor}
              exponent={total.exponent}
              currency={total.currency}
              size="sm"
              tone="muted"
              testID="send-receipt-total"
            />
          </Row>
        ) : null}
        {payment.provider_ref !== undefined ? (
          <Row label="Provider ref">
            <Text style={styles.mono}>{payment.provider_ref}</Text>
          </Row>
        ) : null}
      </Card>
      <Button label="Done" onPress={onDone} testID="send-receipt-done" />
      <Button label="Send another" variant="ghost" onPress={onSendAnother} />
    </ScrollView>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View style={styles.rowLine}>
      <Text style={styles.rowLabel}>{label}</Text>
      <View style={styles.rowValue}>{children}</View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: {
    flex: 1,
    backgroundColor: palette.background,
  },
  stepContent: {
    padding: spacing.lg,
    gap: spacing.lg,
  },
  stepTitle: {
    fontSize: typography.title,
    fontWeight: '800',
    color: palette.text,
  },
  currencyRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  currencyChip: {
    minHeight: 38,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
  },
  parseError: {
    color: palette.danger,
    fontSize: typography.caption,
    fontWeight: '600',
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
  row: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  grow: {
    flex: 1,
  },
  rowLine: {
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
