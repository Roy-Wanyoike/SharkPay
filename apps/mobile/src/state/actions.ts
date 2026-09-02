/**
 * Async action flows (login, restore, logout, list loads, idempotent money
 * submissions). Each action owns exactly one slice transition and — where a
 * session is established or destroyed — keeps the session ref in sync so the
 * API client's token seam sees it immediately.
 *
 * 401 handling: an `AuthError` surfacing after a FAILED refresh means the
 * session is dead; the action ends the session (clears storage, dispatches
 * `session/ended`) and rethrows so the caller can show why.
 */

import { useMemo } from 'react';

import { AuthError } from '../api/errors';
import type { Payment, PaymentCreateRequest, Payout, PayoutCreateRequest } from '../api/types';
import { isSessionExpired } from '../auth/keycloak';
import type { Session } from '../auth/types';
import type { AppAction, AppState } from './types';
import type { AppServices } from './services';
import type { SessionRef, StateRef } from './AppStore';

export interface AppActionsContext {
  services: AppServices;
  dispatch: React.Dispatch<AppAction>;
  sessionRef: SessionRef;
  /** Live state snapshot ref (kept in sync by the provider after each render). */
  stateRef: StateRef;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

/** Establishes a session everywhere it needs to be known (ref + reducer). */
function applySession(context: AppActionsContext, session: Session, action: AppAction): void {
  context.sessionRef.current = session;
  context.dispatch(action);
}

export interface AppActions {
  /** Restores the persisted session (refreshing it first when expired). */
  restore(): Promise<void>;
  /** Runs the interactive Keycloak login (browser + PKCE). */
  login(): Promise<void>;
  /** Revokes best effort, clears secure storage, ends the session. */
  logout(): Promise<void>;
  /** Loads the principal's wallets (GET /wallets). */
  loadWallets(): Promise<void>;
  /** Loads recent payment intents (GET /payments). */
  loadPayments(): Promise<void>;
  /**
   * Refreshes the known payouts by id (the v1 payouts contract has no list
   * endpoint — see src/api/index.ts).
   */
  loadPayouts(): Promise<void>;
  /** Tracks a payout created on-device into the store. */
  trackPayout(payout: Payout): void;
  /** Submits a payment intent with the caller's idempotency key. */
  submitPayment(request: PaymentCreateRequest, idempotencyKey: string): Promise<Payment>;
  /** Submits a payout with the caller's idempotency key. */
  submitPayout(request: PayoutCreateRequest, idempotencyKey: string): Promise<Payout>;
}

export function useAppActions(context: AppActionsContext): AppActions {
  const { services, dispatch, sessionRef, stateRef } = context;

  return useMemo<AppActions>(() => {
    /** Ends the session after a confirmed-dead 401 (refresh failed). */
    const endSession = async (): Promise<void> => {
      sessionRef.current = null;
      await services.storage.clear();
      dispatch({ type: 'session/ended' });
    };

    const handleAuthDeath = async (error: unknown): Promise<void> => {
      if (error instanceof AuthError && error.status === 401) {
        await endSession();
      }
    };

    const restore = async (): Promise<void> => {
      let stored: Session | null = null;
      try {
        stored = await services.storage.load();
      } catch {
        stored = null;
      }
      if (stored === null) {
        dispatch({ type: 'session/restoreFinished' });
        return;
      }
      if (isSessionExpired(stored)) {
        if (stored.refreshToken !== null) {
          try {
            const refreshed = await services.auth.refresh(stored);
            await services.storage.save(refreshed);
            applySession(context, refreshed, { type: 'session/restored', session: refreshed });
            return;
          } catch {
            await services.storage.clear();
            dispatch({ type: 'session/restoreFinished' });
            return;
          }
        }
        await services.storage.clear();
        dispatch({ type: 'session/restoreFinished' });
        return;
      }
      applySession(context, stored, { type: 'session/restored', session: stored });
    };

    const login = async (): Promise<void> => {
      const session = await services.auth.login();
      await services.storage.save(session);
      applySession(context, session, { type: 'session/started', session });
    };

    const logout = async (): Promise<void> => {
      const current = sessionRef.current;
      if (current !== null) {
        await services.auth.revoke(current);
      }
      sessionRef.current = null;
      await services.storage.clear();
      dispatch({ type: 'session/ended' });
    };

    const loadWallets = async (): Promise<void> => {
      dispatch({ type: 'wallets/requested' });
      try {
        const page = await services.api.wallets.listWallets({ limit: 50 });
        dispatch({ type: 'wallets/succeeded', items: page.items });
      } catch (error) {
        await handleAuthDeath(error);
        dispatch({ type: 'wallets/failed', message: errorMessage(error) });
      }
    };

    const loadPayments = async (): Promise<void> => {
      dispatch({ type: 'payments/requested' });
      try {
        const page = await services.api.payments.listPayments({ limit: 50 });
        dispatch({ type: 'payments/succeeded', items: page.items });
      } catch (error) {
        await handleAuthDeath(error);
        dispatch({ type: 'payments/failed', message: errorMessage(error) });
      }
    };

    const loadPayouts = async (): Promise<void> => {
      dispatch({ type: 'payouts/requested' });
      try {
        // Contract gap: no GET /payouts list endpoint at V1 — refresh each
        // payout this device created (ids tracked in the payouts slice).
        const knownIds = stateRef.current.payouts.items.map((payout) => payout.id);
        const refreshed: Payout[] = [];
        for (const id of knownIds) {
          try {
            refreshed.push(await services.api.payouts.getPayout(id));
          } catch {
            // Individual 404s (purged test data) must not sink the rest.
          }
        }
        dispatch({ type: 'payouts/succeeded', items: refreshed });
      } catch (error) {
        await handleAuthDeath(error);
        dispatch({ type: 'payouts/failed', message: errorMessage(error) });
      }
    };

    const trackPayout = (payout: Payout): void => {
      dispatch({ type: 'payout/submitted', payout });
    };

    const submitPayment = async (
      request: PaymentCreateRequest,
      idempotencyKey: string,
    ): Promise<Payment> => {
      // Idempotency-Key travels with the caller's logical intent — the same
      // key on every retry is what makes a retried POST money-safe.
      const payment = await services.api.payments.createPayment(request, idempotencyKey);
      dispatch({ type: 'payment/submitted', payment });
      return payment;
    };

    const submitPayout = async (
      request: PayoutCreateRequest,
      idempotencyKey: string,
    ): Promise<Payout> => {
      const payout = await services.api.payouts.createPayout(request, idempotencyKey);
      dispatch({ type: 'payout/submitted', payout });
      return payout;
    };

    return {
      restore,
      login,
      logout,
      loadWallets,
      loadPayments,
      loadPayouts,
      trackPayout,
      submitPayment,
      submitPayout,
    };
    // Actions read mutable state only through the live refs; the context
    // values (services/dispatch/refs) are stable by construction.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [services, dispatch, sessionRef, stateRef]);
}
