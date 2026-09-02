/**
 * Type-level tests: the SDK's types must be an exact projection of the
 * contracts (expectTypeOf assertions are verified by `tsc -p
 * tsconfig.test.json`, not just at runtime).
 */

import { describe, expectTypeOf, it } from 'vitest';
import type {
  Currency,
  ErrorEnvelope,
  Money,
  Page,
} from '../src/types/common.js';
import type {
  ListPaymentsQuery,
  NextAction,
  Payment,
  PaymentCreateRequest,
  PaymentId,
  PaymentState,
  Rail,
  TerminalPaymentState,
} from '../src/types/payments.js';
import type {
  BankDestination,
  MpesaDestination,
  OnChainDestination,
  Payout,
  PayoutCreateRequest,
  PayoutDestination,
  PayoutState,
} from '../src/types/payouts.js';
import type {
  Transfer,
  TransferCreateRequest,
  TransferState,
} from '../src/types/transfers.js';
import type {
  ClosedWallet,
  OpenWallet,
  StatementEntry,
  Wallet,
  WalletBalances,
  WalletStatus,
} from '../src/types/wallets.js';
import type {
  Conversion,
  Quote,
  QuoteState,
  Rate,
} from '../src/types/fx.js';
import type {
  EventName,
  WebhookEndpoint,
  WebhookEndpointCreateRequest,
} from '../src/types/webhooks.js';
import type {
  CloudEvent,
  CloudEventOf,
  EventData,
  EventPayloadMap,
  PayoutEventData,
  PaymentEventData,
  RiskCaseEventData,
  WalletBalanceEventData,
  WebhookEvent,
} from '../src/events.js';
import {
  failedPaymentFixture,
  payoutFixture,
  transferFixture,
  walletFixture,
} from './helpers.js';

describe('common types', () => {
  it('Currency is the exact 6-value enum', () => {
    expectTypeOf<Currency>().toEqualTypeOf<'KES' | 'USD' | 'EUR' | 'GBP' | 'USDC' | 'USDT'>();
  });

  it('Money has exactly amount_minor / currency / exponent', () => {
    expectTypeOf<Money>().toEqualTypeOf<{
      amount_minor: number;
      currency: Currency;
      exponent: number;
    }>();
  });

  it('ErrorEnvelope wraps the error body', () => {
    expectTypeOf<ErrorEnvelope['error']>().toEqualTypeOf<{
      code: string;
      message: string;
      request_id: string;
      details?: Record<string, unknown>;
    }>();
  });

  it('Page carries items and an optional nullable cursor', () => {
    expectTypeOf<Page<number>['items']>().toEqualTypeOf<number[]>();
    expectTypeOf<Page<number>['next_cursor']>().toEqualTypeOf<string | null | undefined>();
  });
});

describe('payments types', () => {
  it('PaymentState is the exact 9-value enum from payments.yaml', () => {
    expectTypeOf<PaymentState>().toEqualTypeOf<
      | 'CREATED'
      | 'PENDING_PROVIDER'
      | 'PROCESSING'
      | 'SUCCEEDED'
      | 'FAILED'
      | 'EXPIRED'
      | 'REVERSED'
      | 'BLOCKED'
      | 'CANCELLED'
    >();
  });

  it('Rail is the exact 4-value enum', () => {
    expectTypeOf<Rail>().toEqualTypeOf<'honeycoin' | 'mpesa' | 'bank' | 'on_chain'>();
  });

  it('TerminalPaymentState is the six terminal states', () => {
    expectTypeOf<TerminalPaymentState>().toEqualTypeOf<
      'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'REVERSED' | 'BLOCKED' | 'CANCELLED'
    >();
  });

  it('NextAction.type is the none literal', () => {
    expectTypeOf<NextAction['type']>().toEqualTypeOf<'none'>();
  });

  it('Payment is a discriminated union: FAILED narrows failure_reason to string', () => {
    const payment = failedPaymentFixture();
    if (payment.state === 'FAILED') {
      expectTypeOf(payment.failure_reason).toEqualTypeOf<string>();
      expectTypeOf(payment.state).toEqualTypeOf<'FAILED'>();
    } else {
      expectTypeOf(payment.state).not.toEqualTypeOf<'FAILED'>();
    }
  });

  it('Payment requires the contract-mandated fields and allows optional metadata/updated_at', () => {
    expectTypeOf<Payment>().toMatchTypeOf<{
      id: PaymentId;
      amount: Money;
      fee: Money;
      destination_wallet: string;
      rail: Rail;
      next_action: NextAction;
      expires_at: string;
      created_at: string;
    }>();
  });

  it('PaymentCreateRequest matches the create schema', () => {
    expectTypeOf<PaymentCreateRequest>().toEqualTypeOf<{
      amount_minor: number;
      currency: Currency;
      destination_wallet: string;
      rail?: Rail | undefined;
      metadata?: Record<string, unknown> | undefined;
      expires_in_seconds?: number | undefined;
    }>();
  });

  it('ListPaymentsQuery carries the documented filters', () => {
    expectTypeOf<ListPaymentsQuery>().toMatchTypeOf<{
      state?: PaymentState | undefined;
      principal_id?: string | undefined;
      created_from?: string | undefined;
      created_to?: string | undefined;
    }>();
  });
});

describe('payouts types', () => {
  it('PayoutState is the exact 9-value payout enum', () => {
    expectTypeOf<PayoutState>().toEqualTypeOf<
      | 'CREATED'
      | 'PENDING_RISK'
      | 'PROCESSING'
      | 'SENT'
      | 'SUCCEEDED'
      | 'FAILED'
      | 'RETURNED'
      | 'BLOCKED'
      | 'CANCELLED'
    >();
  });

  it('PayoutDestination is discriminated by type', () => {
    const destination: PayoutDestination = payoutFixture().destination;
    if (destination.type === 'mpesa') {
      expectTypeOf(destination).toEqualTypeOf<MpesaDestination>();
      expectTypeOf(destination.msisdn).toEqualTypeOf<string>();
    } else if (destination.type === 'bank') {
      expectTypeOf(destination).toEqualTypeOf<BankDestination>();
    } else {
      expectTypeOf(destination).toEqualTypeOf<OnChainDestination>();
      expectTypeOf(destination.network).toEqualTypeOf<'base' | 'ethereum' | 'polygon'>();
    }
  });

  it('Payout union: RETURNED narrows return_reason, FAILED narrows failure_reason', () => {
    const payout: Payout = payoutFixture();
    if (payout.state === 'RETURNED') {
      expectTypeOf(payout.return_reason).toEqualTypeOf<string>();
    } else if (payout.state === 'FAILED') {
      expectTypeOf(payout.failure_reason).toEqualTypeOf<string>();
    } else {
      expectTypeOf(payout.state).not.toEqualTypeOf<'FAILED' | 'RETURNED'>();
    }
  });

  it('PayoutCreateRequest matches the create schema', () => {
    expectTypeOf<PayoutCreateRequest>().toEqualTypeOf<{
      source_wallet: string;
      amount_minor: number;
      currency: Currency;
      destination: PayoutDestination;
      rail?: 'mpesa' | 'bank' | 'on_chain' | undefined;
      metadata?: Record<string, unknown> | undefined;
      expires_in_seconds?: number | undefined;
    }>();
  });
});

describe('transfers types', () => {
  it('TransferState is CREATED | SUCCEEDED | FAILED', () => {
    expectTypeOf<TransferState>().toEqualTypeOf<'CREATED' | 'SUCCEEDED' | 'FAILED'>();
  });

  it('Transfer union: SUCCEEDED narrows entry_id, FAILED narrows failure_reason', () => {
    const transfer: Transfer = transferFixture();
    if (transfer.state === 'SUCCEEDED') {
      expectTypeOf(transfer.entry_id).toEqualTypeOf<string>();
    } else if (transfer.state === 'FAILED') {
      expectTypeOf(transfer.failure_reason).toEqualTypeOf<string>();
    } else {
      expectTypeOf(transfer.state).toEqualTypeOf<'CREATED'>();
    }
  });

  it('TransferCreateRequest matches the create schema', () => {
    expectTypeOf<TransferCreateRequest>().toEqualTypeOf<{
      source_wallet: string;
      destination_wallet: string;
      amount_minor: number;
      currency: Currency;
      metadata?: Record<string, unknown> | undefined;
    }>();
  });
});

describe('wallets types', () => {
  it('WalletStatus is active | frozen | closed', () => {
    expectTypeOf<WalletStatus>().toEqualTypeOf<'active' | 'frozen' | 'closed'>();
  });

  it('WalletBalances has the three partitions', () => {
    expectTypeOf<WalletBalances>().toEqualTypeOf<{
      available: Money;
      pending: Money;
      held: Money;
    }>();
  });

  it('Wallet union: closed narrows closed_at', () => {
    const wallet: Wallet = walletFixture();
    if (wallet.status === 'closed') {
      expectTypeOf(wallet).toEqualTypeOf<ClosedWallet>();
      expectTypeOf(wallet.closed_at).toEqualTypeOf<string>();
    } else {
      expectTypeOf(wallet).toEqualTypeOf<OpenWallet>();
    }
  });

  it('StatementEntry carries the ledger line fields', () => {
    expectTypeOf<StatementEntry['entry_type']>().toEqualTypeOf<
      'capture' | 'hold' | 'release' | 'reversal' | 'fee' | 'fx' | 'adjustment'
    >();
    expectTypeOf<StatementEntry['direction']>().toEqualTypeOf<'debit' | 'credit'>();
    expectTypeOf<StatementEntry['source']>().toEqualTypeOf<
      'payments' | 'payouts' | 'transfers' | 'fx' | 'fees' | 'ops'
    >();
  });
});

describe('fx types', () => {
  it('QuoteState is QUOTED | LOCKED | EXECUTED | EXPIRED', () => {
    expectTypeOf<QuoteState>().toEqualTypeOf<'QUOTED' | 'LOCKED' | 'EXECUTED' | 'EXPIRED'>();
  });

  it('Rate is the exact integer rate shape', () => {
    expectTypeOf<Rate>().toEqualTypeOf<{
      value_minor: number;
      exponent: number;
      base_currency: Currency;
      quote_currency: Currency;
    }>();
  });

  it('Quote and Conversion match the response schemas (entry_id required on Conversion)', () => {
    expectTypeOf<Quote['id']>().toEqualTypeOf<string>();
    expectTypeOf<Conversion['state']>().toEqualTypeOf<'EXECUTED'>();
    expectTypeOf<Conversion['entry_id']>().toEqualTypeOf<string>();
    expectTypeOf<Conversion['quote_id']>().toEqualTypeOf<string>();
  });
});

describe('webhook + event types', () => {
  it('EventName is the exact 17-value catalog', () => {
    expectTypeOf<EventName>().toEqualTypeOf<
      | 'payment.created'
      | 'payment.pending_provider'
      | 'payment.succeeded'
      | 'payment.failed'
      | 'payment.expired'
      | 'payment.reversed'
      | 'payout.created'
      | 'payout.processing'
      | 'payout.sent'
      | 'payout.succeeded'
      | 'payout.failed'
      | 'payout.returned'
      | 'transfer.succeeded'
      | 'fx.quote.locked'
      | 'fx.conversion.executed'
      | 'wallet.balance.changed'
      | 'risk.case.opened'
    >();
  });

  it('WebhookEndpoint and WebhookEndpointCreateRequest match the schemas', () => {
    expectTypeOf<WebhookEndpoint['state']>().toEqualTypeOf<'active' | 'dead'>();
    expectTypeOf<WebhookEndpoint['secret']>().toEqualTypeOf<string | undefined>();
    expectTypeOf<WebhookEndpointCreateRequest>().toEqualTypeOf<{
      url: string;
      events: EventName[];
      secret: string;
    }>();
  });

  it('CloudEvent envelope is CloudEvents 1.0-aligned with a literal specversion', () => {
    expectTypeOf<CloudEvent['specversion']>().toEqualTypeOf<'1.0'>();
    expectTypeOf<WebhookEvent['data']>().toEqualTypeOf<EventData>();
  });

  it('EventPayloadMap routes every catalog name to its payload type', () => {
    expectTypeOf<EventPayloadMap['payment.succeeded']>().toEqualTypeOf<PaymentEventData>();
    expectTypeOf<EventPayloadMap['payout.failed']>().toEqualTypeOf<PayoutEventData>();
    expectTypeOf<EventPayloadMap['wallet.balance.changed']>().toEqualTypeOf<WalletBalanceEventData>();
    expectTypeOf<EventPayloadMap['risk.case.opened']>().toEqualTypeOf<RiskCaseEventData>();
    expectTypeOf<CloudEventOf<'payment.succeeded'>['data']>().toEqualTypeOf<PaymentEventData>();
    expectTypeOf<CloudEventOf<'risk.case.opened'>['data']>().toEqualTypeOf<RiskCaseEventData>();
  });

  it('PaymentEventData mirrors the webhook payload schema', () => {
    expectTypeOf<PaymentEventData>().toMatchTypeOf<{
      payment_id: string;
      state: PaymentState;
      amount: Money;
      fee: Money;
      destination_wallet: string;
      rail: Rail;
    }>();
    expectTypeOf<PayoutEventData['destination_type']>().toEqualTypeOf<
      'mpesa' | 'bank' | 'on_chain'
    >();
  });
});
