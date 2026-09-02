/**
 * The pure app reducer: session phases, list slice transitions and the
 * idempotent upsert semantics for freshly submitted records.
 */

import { describe, expect, it } from '@jest/globals';

import type { Session } from '../../auth/types';
import { appReducer } from '../reducer';
import { initialAppState } from '../types';
import type { Payment, Wallet } from '../../api/types';

const session: Session = {
  accessToken: 'at',
  refreshToken: 'rt',
  idToken: null,
  accessTokenExpiresAtMs: 4_102_444_800_000,
  claims: null,
};

const wallet: Wallet = {
  id: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
  principal_id: 'prin_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
  currency: 'KES',
  status: 'ACTIVE',
  balances: { total_minor: 150000, available_minor: 140000, held_minor: 10000 },
  created_at: '2026-01-02T00:00:00Z',
} as unknown as Wallet;

const payment = (id: string): Payment =>
  ({
    id,
    principal_id: 'prin_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    amount_minor: 150000,
    currency: 'KES',
    fee_minor: 750,
    state: 'PENDING_PROVIDER',
    created_at: '2026-01-02T00:00:00Z',
  }) as unknown as Payment;

describe('session lifecycle', () => {
  it('starts in the restoring phase with idle lists', () => {
    expect(initialAppState.sessionPhase).toBe('restoring');
    expect(initialAppState.wallets.status).toBe('idle');
  });

  it('authenticate actions move the phase and store the session', () => {
    for (const type of ['session/restored', 'session/started', 'session/refreshed'] as const) {
      const next = appReducer(initialAppState, { type, session });
      expect(next.sessionPhase).toBe('authenticated');
      expect(next.session?.accessToken).toBe('at');
    }
  });

  it('restoreFinished only lands when still restoring', () => {
    const anonymous = appReducer(initialAppState, { type: 'session/restoreFinished' });
    expect(anonymous.sessionPhase).toBe('anonymous');
    // Already authenticated → no-op (a late restore signal cannot regress)
    const authenticated = appReducer(
      { ...initialAppState, session, sessionPhase: 'authenticated' },
      { type: 'session/restoreFinished' },
    );
    expect(authenticated.sessionPhase).toBe('authenticated');
  });

  it('session/ended wipes the session AND resets every list slice', () => {
    const loaded = appReducer(
      { ...initialAppState, session, sessionPhase: 'authenticated' },
      { type: 'wallets/succeeded', items: [wallet] },
    );
    const ended = appReducer(loaded, { type: 'session/ended' });
    expect(ended.session).toBeNull();
    expect(ended.sessionPhase).toBe('anonymous');
    expect(ended.wallets).toEqual(initialAppState.wallets);
    expect(ended.payments.status).toBe('idle');
    expect(ended.payouts.status).toBe('idle');
  });
});

describe('list slices', () => {
  it('wallets: idle → loading → ready with items', () => {
    const loading = appReducer(initialAppState, { type: 'wallets/requested' });
    expect(loading.wallets.status).toBe('loading');
    const ready = appReducer(loading, { type: 'wallets/succeeded', items: [wallet] });
    expect(ready.wallets).toEqual({ status: 'ready', items: [wallet], error: null });
  });

  it('wallets: failure keeps prior items and records the message', () => {
    const withItems = appReducer(initialAppState, { type: 'wallets/succeeded', items: [wallet] });
    const failed = appReducer(withItems, { type: 'wallets/failed', message: 'upstream down' });
    expect(failed.wallets.status).toBe('error');
    expect(failed.wallets.items).toEqual([wallet]);
    expect(failed.wallets.error).toBe('upstream down');
  });

  it('errors/dismissed clears the slice error without touching items', () => {
    const failed = appReducer(initialAppState, { type: 'payments/failed', message: 'boom' });
    const dismissed = appReducer(failed, { type: 'errors/dismissed', scope: 'payments' });
    expect(dismissed.payments.error).toBeNull();
    expect(dismissed.payments.status).toBe('error');
  });
});

describe('submitted records (idempotent upsert)', () => {
  it('prepends a new payment and marks the slice ready', () => {
    const next = appReducer(initialAppState, {
      type: 'payment/submitted',
      payment: payment('pay_1'),
    });
    expect(next.payments.status).toBe('ready');
    expect(next.payments.items.map((p) => p.id)).toEqual(['pay_1']);
  });

  it('replaces in place instead of duplicating (same id = same idempotent submission)', () => {
    const withBoth = appReducer(initialAppState, {
      type: 'payments/succeeded',
      items: [payment('pay_1'), payment('pay_2')],
    });
    const reupserted = appReducer(withBoth, {
      type: 'payment/submitted',
      payment: payment('pay_1'),
    });
    expect(reupserted.payments.items.map((p) => p.id)).toEqual(['pay_1', 'pay_2']);
  });
});
