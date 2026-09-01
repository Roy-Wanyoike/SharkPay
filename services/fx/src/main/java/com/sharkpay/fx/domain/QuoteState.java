package com.sharkpay.fx.domain;

/**
 * Quote state machine (docs/STATE-MACHINES.md &#167;4):
 * {@code QUOTED → LOCKED → EXECUTED | EXPIRED}.
 *
 * <ul>
 *   <li>QUOTED — indicative, TTL running (default 30 s, configurable;
 *       request override 5..3600 s)</li>
 *   <li>LOCKED — rate guaranteed; a locked quote <b>never auto-expires</b>
 *       (expiry of a locked quote is a p1 incident per the state machine
 *       doc; the expiry sweep therefore only touches QUOTED quotes)</li>
 *   <li>EXECUTED — the 4-leg conversion entry has been posted</li>
 *   <li>EXPIRED — TTL elapsed while still QUOTED</li>
 * </ul>
 */
public enum QuoteState {
    QUOTED,
    LOCKED,
    EXECUTED,
    EXPIRED
}
