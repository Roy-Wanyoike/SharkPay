/** Runtime guards for ids, enums and catalog names. */

import { describe, expect, it } from 'vitest';
import { isRequestId } from '../src/types/common.js';
import { isPaymentId } from '../src/types/payments.js';
import { isPayoutId } from '../src/types/payouts.js';
import { isTransferId } from '../src/types/transfers.js';
import { isWalletId } from '../src/types/wallets.js';
import { isConversionId, isQuoteId } from '../src/types/fx.js';
import { isCaseId, isEventName, isWebhookEndpointId } from '../src/types/webhooks.js';
import {
  CASE_ID_PATTERN,
  CONVERSION_ID_PATTERN,
  PAYMENT_ID_PATTERN,
  PAYOUT_ID_PATTERN,
  QUOTE_ID_PATTERN,
  REQUEST_ID_PATTERN,
  SIGNATURE_HEADER_PATTERN,
  TRANSFER_ID_PATTERN,
  WALLET_ID_PATTERN,
  WEBHOOK_ENDPOINT_ID_PATTERN,
} from '../src/index.js';
import {
  CONVERSION_ID,
  ENDPOINT_ID,
  PAYMENT_ID,
  PAYOUT_ID,
  QUOTE_ID,
  REQUEST_ID,
  TRANSFER_ID,
  WALLET_A,
} from './helpers.js';

const CASE_ID = 'case_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';

describe('id guards', () => {
  it.each([
    ['pay_', isPaymentId, PAYMENT_ID],
    ['pot_', isPayoutId, PAYOUT_ID],
    ['trf_', isTransferId, TRANSFER_ID],
    ['wal_', isWalletId, WALLET_A],
    ['fxq_', isQuoteId, QUOTE_ID],
    ['cnv_', isConversionId, CONVERSION_ID],
    ['wh_', isWebhookEndpointId, ENDPOINT_ID],
    ['case_', isCaseId, CASE_ID],
  ] as const)('accepts well-formed %s ids and rejects malformed ones', (_prefix, guard, valid) => {
    expect(guard(valid)).toBe(true);
    expect(guard(`wrong_${valid.slice(5)}`)).toBe(false);
    // truly too short: 26-char body minus 7 = 19 chars < the 20 minimum
    expect(guard(valid.slice(0, -7))).toBe(false);
    expect(guard(123)).toBe(false);
    expect(guard(null)).toBe(false);
    expect(guard(undefined)).toBe(false);
  });

  it('req_ ids accept any non-empty body (opaque server-generated ids)', () => {
    expect(isRequestId(REQUEST_ID)).toBe(true);
    expect(isRequestId(`wrong_${REQUEST_ID.slice(5)}`)).toBe(false);
    expect(isRequestId('req_')).toBe(false); // empty body is not an id
    expect(isRequestId(123)).toBe(false);
    expect(isRequestId(null)).toBe(false);
    expect(isRequestId(undefined)).toBe(false);
  });

  it('exposes the raw patterns for external validation', () => {
    expect(PAYMENT_ID_PATTERN.test(PAYMENT_ID)).toBe(true);
    expect(PAYOUT_ID_PATTERN.test(PAYOUT_ID)).toBe(true);
    expect(TRANSFER_ID_PATTERN.test(TRANSFER_ID)).toBe(true);
    expect(WALLET_ID_PATTERN.test(WALLET_A)).toBe(true);
    expect(QUOTE_ID_PATTERN.test(QUOTE_ID)).toBe(true);
    expect(CONVERSION_ID_PATTERN.test(CONVERSION_ID)).toBe(true);
    expect(WEBHOOK_ENDPOINT_ID_PATTERN.test(ENDPOINT_ID)).toBe(true);
    expect(CASE_ID_PATTERN.test(CASE_ID)).toBe(true);
    expect(REQUEST_ID_PATTERN.test(REQUEST_ID)).toBe(true);
    expect(REQUEST_ID_PATTERN.test('req_')).toBe(false);
  });

  it('validates the signature header pattern t=<unix>,v1=<64 hex>', () => {
    expect(SIGNATURE_HEADER_PATTERN.test('t=1767312000,v1=' + 'a'.repeat(64))).toBe(true);
    expect(SIGNATURE_HEADER_PATTERN.test('t=1767312000,v1=' + 'a'.repeat(63))).toBe(false);
    expect(SIGNATURE_HEADER_PATTERN.test('t=,v1=' + 'a'.repeat(64))).toBe(false);
    expect(SIGNATURE_HEADER_PATTERN.test('t=1767312000,v1=' + 'g'.repeat(64))).toBe(false);
  });
});

describe('catalog guards', () => {
  it('isEventName accepts catalog names and rejects unknown ones', () => {
    expect(isEventName('payment.succeeded')).toBe(true);
    expect(isEventName('risk.case.opened')).toBe(true);
    expect(isEventName('payments.payment.succeeded.v1')).toBe(false); // Kafka topic names are not webhook catalog names
    expect(isEventName('payment.settled')).toBe(false);
    expect(isEventName(42)).toBe(false);
  });
});
