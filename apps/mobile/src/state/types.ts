/**
 * Store types: the app state shape and the action union consumed by the
 * pure reducer (src/state/reducer.ts). Session, wallets, payments, payouts —
 * exactly the four slices the mission pins.
 */

import type { Payment, Payout, Wallet } from '../api/types';
import type { Session } from '../auth/types';

/** Async slice status (idle → loading → ready | error). */
export type LoadStatus = 'idle' | 'loading' | 'ready' | 'error';

/** A list slice with its load status and last error message. */
export interface Loadable<T> {
  status: LoadStatus;
  items: T[];
  /** Last failure message (rendered by ErrorView); null when none. */
  error: string | null;
}

/** Session lifecycle phase — drives the root navigator's auth gate. */
export type SessionPhase = 'restoring' | 'anonymous' | 'authenticated';

export interface AppState {
  session: Session | null;
  sessionPhase: SessionPhase;
  wallets: Loadable<Wallet>;
  payments: Loadable<Payment>;
  payouts: Loadable<Payout>;
}

export const initialAppState: AppState = {
  session: null,
  sessionPhase: 'restoring',
  wallets: { status: 'idle', items: [], error: null },
  payments: { status: 'idle', items: [], error: null },
  payouts: { status: 'idle', items: [], error: null },
};

export type AppAction =
  /** A persisted session was restored from secure storage (post-refresh). */
  | { type: 'session/restored'; session: Session }
  /** A fresh interactive login completed. */
  | { type: 'session/started'; session: Session }
  /** A silent refresh replaced the session (401 recovery path). */
  | { type: 'session/refreshed'; session: Session }
  /** No valid session: restore found none / logout / expired. */
  | { type: 'session/ended' }
  /** Restore finished without a usable session (distinct from explicit logout). */
  | { type: 'session/restoreFinished' }
  | { type: 'wallets/requested' }
  | { type: 'wallets/succeeded'; items: Wallet[] }
  | { type: 'wallets/failed'; message: string }
  | { type: 'payments/requested' }
  | { type: 'payments/succeeded'; items: Payment[] }
  | { type: 'payments/failed'; message: string }
  | { type: 'payment/submitted'; payment: Payment }
  | { type: 'payouts/requested' }
  | { type: 'payouts/succeeded'; items: Payout[] }
  | { type: 'payouts/failed'; message: string }
  | { type: 'payout/submitted'; payout: Payout }
  | { type: 'errors/dismissed'; scope: 'wallets' | 'payments' | 'payouts' };

/** Creates an idle list slice (used by the reducer's reset paths). */
export function idleLoadable<T>(): Loadable<T> {
  return { status: 'idle', items: [], error: null };
}

/** Creates a loading slice that KEEPS the previous items (refresh UX). */
export function loading<T>(slice: Loadable<T>): Loadable<T> {
  return { status: 'loading', items: slice.items, error: null };
}
