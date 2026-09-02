/**
 * The app store: React Context + useReducer (mission-pinned architecture).
 *
 * - `AppProvider` wires `createServices` (real or overridden via props for
 *   tests/smoke suites), runs the once-per-mount session restore, and keeps
 *   a live session ref the API client's token seam reads per attempt.
 * - `useApp()` exposes { state, actions, services, dispatch }.
 * - `useAppActions()` (src/state/actions.ts) holds every async flow.
 */

import React, {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from 'react';

import type { AppServices, ServiceOverrides } from './services';
import { createServices } from './services';
import { appReducer } from './reducer';
import { isSessionExpired } from '../auth/keycloak';
import type { Session } from '../auth/types';
import { initialAppState, type AppAction, type AppState } from './types';
import { useAppActions, type AppActions } from './actions';

/** Live session accessor (ref-backed; never triggers renders). */
export interface SessionRef {
  current: Session | null;
}

/** Live state snapshot ref (synced after every render). */
export interface StateRef {
  current: AppState;
}

export interface AppStoreValue {
  state: AppState;
  dispatch: React.Dispatch<AppAction>;
  services: AppServices;
  sessionRef: SessionRef;
  actions: AppActions;
}

const AppStoreContext = createContext<AppStoreValue | null>(null);

export interface AppProviderProps {
  children: ReactNode;
  /**
   * Test/smoke-suite seam (must be referentially stable across renders —
   * memoise at the call site). Production renders pass nothing.
   */
  overrides?: ServiceOverrides;
}

export function AppProvider({ children, overrides }: AppProviderProps) {
  const [state, dispatch] = useReducer(appReducer, initialAppState);
  const sessionRef = useRef<Session | null>(null);
  const stateRef = useRef<AppState>(initialAppState);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  const services = useMemo(
    () =>
      createServices({
        ...(overrides ?? {}),
        getSession: () => sessionRef.current,
        onSessionRefreshed: (session: Session) => {
          sessionRef.current = session;
          dispatch({ type: 'session/refreshed', session });
        },
      }),
    // dispatch from useReducer is stable; overrides must be memoised by the
    // caller (documented on AppProviderProps).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  const actions = useAppActions({ services, dispatch, sessionRef, stateRef });

  // Once-per-mount session restore from secure storage (refreshing first
  // when the stored access token has expired).
  useEffect(() => {
    void actions.restore();
    // actions is stable (memoised on stable inputs).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const value = useMemo<AppStoreValue>(
    () => ({ state, dispatch, services, sessionRef, actions }),
    [state, services, actions],
  );

  return <AppStoreContext.Provider value={value}>{children}</AppStoreContext.Provider>;
}

export function useApp(): AppStoreValue {
  const value = useContext(AppStoreContext);
  if (value === null) {
    throw new Error('useApp must be used inside <AppProvider>');
  }
  return value;
}

/** Convenience selector: is the session phase past the splash gate? */
export function useSessionPhase(): AppState['sessionPhase'] {
  return useApp().state.sessionPhase;
}

/** Re-exported for action tests that drive the reducer directly. */
export { isSessionExpired };
