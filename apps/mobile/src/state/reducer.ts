/**
 * The pure app reducer — no side effects, no API access; every behavioural
 * decision about session/list state lives here so it is exhaustively
 * unit-testable (src/state/__tests__/reducer.test.ts).
 */

import type { Loadable, AppAction, AppState } from './types';
import { idleLoadable, loading } from './types';

function requested<T>(slice: Loadable<T>): Loadable<T> {
  return loading(slice);
}

function failed<T>(slice: Loadable<T>, message: string): Loadable<T> {
  return { status: 'error', items: slice.items, error: message };
}

function authenticate(state: AppState, session: AppState['session']): AppState {
  return { ...state, session, sessionPhase: 'authenticated' };
}

/**
 * Prepends (or replaces-in-place) a newly created record so re-submissions
 * with the same idempotency key never duplicate rows.
 */
function upsertBy<T extends { id: string }>(items: T[], record: T): T[] {
  const existingIndex = items.findIndex((item) => item.id === record.id);
  if (existingIndex === -1) {
    return [record, ...items];
  }
  const next = [...items];
  next[existingIndex] = record;
  return next;
}

export function appReducer(state: AppState, action: AppAction): AppState {
  switch (action.type) {
    // ── session ───────────────────────────────────────────────────────────
    case 'session/restored':
    case 'session/started':
    case 'session/refreshed':
      return authenticate(state, action.session);
    case 'session/restoreFinished':
      return state.sessionPhase === 'restoring'
        ? { ...state, sessionPhase: 'anonymous' }
        : state;
    case 'session/ended':
      return {
        ...state,
        session: null,
        sessionPhase: 'anonymous',
        wallets: idleLoadable(),
        payments: idleLoadable(),
        payouts: idleLoadable(),
      };

    // ── wallets ───────────────────────────────────────────────────────────
    case 'wallets/requested':
      return { ...state, wallets: requested(state.wallets) };
    case 'wallets/succeeded':
      return { ...state, wallets: { status: 'ready', items: action.items, error: null } };
    case 'wallets/failed':
      return { ...state, wallets: failed(state.wallets, action.message) };

    // ── payments ──────────────────────────────────────────────────────────
    case 'payments/requested':
      return { ...state, payments: requested(state.payments) };
    case 'payments/succeeded':
      return { ...state, payments: { status: 'ready', items: action.items, error: null } };
    case 'payments/failed':
      return { ...state, payments: failed(state.payments, action.message) };
    case 'payment/submitted':
      return {
        ...state,
        payments: {
          status: 'ready',
          items: upsertBy(state.payments.items, action.payment),
          error: null,
        },
      };

    // ── payouts ───────────────────────────────────────────────────────────
    case 'payouts/requested':
      return { ...state, payouts: requested(state.payouts) };
    case 'payouts/succeeded':
      return { ...state, payouts: { status: 'ready', items: action.items, error: null } };
    case 'payouts/failed':
      return { ...state, payouts: failed(state.payouts, action.message) };
    case 'payout/submitted':
      return {
        ...state,
        payouts: {
          status: 'ready',
          items: upsertBy(state.payouts.items, action.payout),
          error: null,
        },
      };

    // ── errors ────────────────────────────────────────────────────────────
    case 'errors/dismissed':
      switch (action.scope) {
        case 'wallets':
          return { ...state, wallets: { ...state.wallets, error: null } };
        case 'payments':
          return { ...state, payments: { ...state.payments, error: null } };
        case 'payouts':
          return { ...state, payouts: { ...state.payouts, error: null } };
      }
      return state;

    default: {
      // Exhaustiveness: an unknown action type is a compile error, not a
      // runtime no-op silently swallowing state-shape drift.
      const exhaustive: never = action;
      throw new Error(`Unhandled app action: ${JSON.stringify(exhaustive)}`);
    }
  }
}
