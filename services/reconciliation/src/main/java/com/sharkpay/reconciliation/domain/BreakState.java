package com.sharkpay.reconciliation.domain;

/**
 * Lifecycle state of a reconciliation break (RB-7):
 *
 * <pre>
 *                 start investigation        resolve (no money moved)
 *   OPEN ───────────────▶ INVESTIGATING ───────────────▶ RESOLVED
 *   │                         │
 *   │                         ├── waive (documented no-fix) ──▶ WAIVED
 *   │                         │
 *   └── compensation executed ─┘  (approve + execute, 4-eyes) ──▶ COMPENSATED
 * </pre>
 *
 * <p>Terminal states ({@code RESOLVED}, {@code COMPENSATED},
 * {@code WAIVED}) never re-open: per DATA-MODEL §4.5 the financial history
 * is append-only, and a wrong resolution is corrected by a new compensation
 * entry, never by editing the break.</p>
 */
public enum BreakState {

    OPEN("open"),
    INVESTIGATING("investigating"),
    RESOLVED("resolved"),
    COMPENSATED("compensated"),
    WAIVED("waived");

    private final String wireName;

    BreakState(String wireName) {
        this.wireName = wireName;
    }

    /** The wire/API/DB name of the state. */
    public String wireName() {
        return wireName;
    }

    public boolean isTerminal() {
        return this == RESOLVED || this == COMPENSATED || this == WAIVED;
    }

    /** True while a break is active (ageable, compensatable). */
    public boolean isActive() {
        return this == OPEN || this == INVESTIGATING;
    }

    /** Parses the wire name (storage/API); never guesses. */
    public static BreakState fromWireName(String wireName) {
        for (BreakState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown break state: " + wireName);
    }
}
