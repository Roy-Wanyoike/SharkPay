package com.sharkpay.reconciliation.domain;

/**
 * State of one reconciliation run (STATE-MACHINES §7.4, BACKEND-DESIGN §4):
 * a run starts {@code RUNNING} and ends in exactly one terminal state —
 * {@code COMPLETED} (the comparison executed: breaks, if any, are recorded)
 * or {@code FAILED} (the provider statement or ledger statement could not
 * be fetched; the failure reason is recorded on the run).
 *
 * <p>Wire names are lowercase on the API and in the {@code recon_runs}
 * table.</p>
 */
public enum ReconRunState {

    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String wireName;

    ReconRunState(String wireName) {
        this.wireName = wireName;
    }

    /** The wire/API/DB name of the state. */
    public String wireName() {
        return wireName;
    }

    public boolean isTerminal() {
        return this != RUNNING;
    }

    /** Parses the wire name (storage/API); never guesses. */
    public static ReconRunState fromWireName(String wireName) {
        for (ReconRunState state : values()) {
            if (state.wireName.equals(wireName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("unknown recon run state: " + wireName);
    }
}
