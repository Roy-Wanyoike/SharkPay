/** CloudEvent envelope parsing and payload typing. */

import { describe, expect, it } from 'vitest';
import { parseWebhookEvent } from '../src/events.js';
import type { PaymentEventData, WebhookEvent } from '../src/events.js';
import { PAYMENT_ID, WALLET_A } from './helpers.js';

/** webhooks.yaml event-delivery example (payment.succeeded). */
const paymentSucceededEvent = {
  id: '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d',
  type: 'payment.succeeded',
  specversion: '1.0',
  source: 'sharkpay/payments',
  subject: PAYMENT_ID,
  occurred_at: '2026-09-01T10:00:05Z',
  data: {
    payment_id: PAYMENT_ID,
    state: 'SUCCEEDED',
    amount: { amount_minor: 150000, currency: 'KES', exponent: 2 },
    fee: { amount_minor: 750, currency: 'KES', exponent: 2 },
    destination_wallet: WALLET_A,
    rail: 'honeycoin',
    entry_id: '0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f',
  },
};

const riskCaseEvent = {
  id: '0192a7c8-4d5e-7f6a-8b9c-0d1e2f3a4b5c',
  type: 'risk.case.opened',
  specversion: '1.0',
  source: 'sharkpay/risk',
  subject: 'case_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
  occurred_at: '2026-09-01T11:00:00Z',
  data: {
    case_id: 'case_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    principal_id: '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d',
    source: 'payments',
    source_ref: '0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f',
    reason: 'velocity_per_hour exceeded',
  },
};

describe('parseWebhookEvent', () => {
  it('validates and returns the payment.succeeded contract example', () => {
    const event = parseWebhookEvent(paymentSucceededEvent);
    expect(event.id).toBe('0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d');
    expect(event.type).toBe('payment.succeeded');
    expect(event.specversion).toBe('1.0');
    expect(event.source).toBe('sharkpay/payments');
    expect(event.subject).toBe(PAYMENT_ID);
    expect(event.occurred_at).toBe('2026-09-01T10:00:05Z');
    expect((event.data as PaymentEventData).state).toBe('SUCCEEDED');
  });

  it('keeps the envelope fields accessible after narrowing on type', () => {
    const event: WebhookEvent = parseWebhookEvent(riskCaseEvent);
    if (event.type === 'risk.case.opened') {
      expect(event.data.case_id).toBe('case_01HZWR4Z7K8Q2N5M9X3V1B6Y0A');
      expect(event.data.reason).toBe('velocity_per_hour exceeded');
      expect(event.data.source).toBe('payments');
    } else {
      throw new Error('expected the risk.case.opened arm');
    }
  });

  it('throws TypeError with field-specific messages on malformed envelopes', () => {
    expect(() => parseWebhookEvent(null)).toThrow(/JSON object/);
    expect(() => parseWebhookEvent('event')).toThrow(/JSON object/);
    expect(() => parseWebhookEvent([paymentSucceededEvent])).toThrow(/JSON object/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, id: 'not-a-uuid' })).toThrow(/'id'/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, id: '' })).toThrow(/'id'/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, type: 'payment.settled' })).toThrow(
      /'type'/,
    );
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, specversion: '1.1' })).toThrow(
      /'specversion'/,
    );
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, source: '' })).toThrow(/'source'/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, subject: 42 })).toThrow(/'subject'/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, occurred_at: '' })).toThrow(
      /'occurred_at'/,
    );
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, data: 'flat' })).toThrow(/'data'/);
    expect(() => parseWebhookEvent({ ...paymentSucceededEvent, data: null })).toThrow(/'data'/);
  });
});
