package com.sharkpay.wallet.domain;

import com.sharkpay.money.Currencies;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A multi-currency balance container: one wallet per principal per currency.
 * The wallet never stores balances itself — the total balance is the
 * projection of {@code ledger.posting.committed.v1} legs against
 * {@link #ledgerAccountId()}, and held funds are the sum of ACTIVE holds
 * (see {@link Balances}).
 *
 * <p>Lifecycle: {@code ACTIVE ⇄ FROZEN} (freeze/unfreeze by compliance, each
 * with an audit reason). A FROZEN wallet blocks new holds but existing holds
 * can still be released or captured (settling an existing commitment is not
 * a new outflow).
 */
public final class Wallet {

    /** Public wallet id pattern (contracts/openapi/v1/wallets.yaml). */
    public static final Pattern ID_PATTERN = Pattern.compile("^wal_[0-9A-Za-z]{20,}$");

    private final String id;
    private final UUID principalId;
    private final String currency;
    private final UUID ledgerAccountId;
    private final Instant createdAt;
    private WalletStatus status;
    private String statusReason;
    private Instant statusChangedAt;
    private Instant updatedAt;

    public Wallet(String id, UUID principalId, String currency, UUID ledgerAccountId,
                  WalletStatus status, String statusReason, Instant statusChangedAt,
                  Instant createdAt, Instant updatedAt) {
        this.id = requireId(id);
        this.principalId = Objects.requireNonNull(principalId, "principalId is required");
        this.currency = Currencies.normalize(currency);
        this.ledgerAccountId = Objects.requireNonNull(ledgerAccountId, "ledgerAccountId is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.statusReason = statusReason;
        this.statusChangedAt = statusChangedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }

    /** Creates a fresh ACTIVE wallet (first status change = its creation). */
    public static Wallet newWallet(String id, UUID principalId, String currency,
                                   UUID ledgerAccountId, Instant at) {
        return new Wallet(id, principalId, currency, ledgerAccountId, WalletStatus.ACTIVE,
                null, null, at, at);
    }

    /** Freezes the wallet, recording the audit reason. ACTIVE → FROZEN. */
    public void freeze(String reason, Instant at) {
        // input validation precedes state checks: a malformed request is a
        // 400 regardless of the wallet's current state
        String auditReason = requireReason(reason, "freeze");
        requireStatus(WalletStatus.ACTIVE, "freeze a frozen wallet");
        transition(WalletStatus.FROZEN, auditReason, at);
    }

    /** Unfreezes the wallet, recording the audit reason. FROZEN → ACTIVE. */
    public void unfreeze(String reason, Instant at) {
        String auditReason = requireReason(reason, "unfreeze");
        requireStatus(WalletStatus.FROZEN, "unfreeze an active wallet");
        transition(WalletStatus.ACTIVE, auditReason, at);
    }

    private void transition(WalletStatus target, String reason, Instant at) {
        this.status = target;
        this.statusReason = reason;
        this.statusChangedAt = at;
        this.updatedAt = at;
    }

    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }

    private void requireStatus(WalletStatus expected, String attempted) {
        if (status != expected) {
            throw new WalletStateException(id, status, attempted);
        }
    }

    private static String requireReason(String reason, String what) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(what + " requires an audit reason");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 512) {
            throw new IllegalArgumentException(what + " reason must be at most 512 characters");
        }
        return trimmed;
    }

    private static String requireId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "wallet id must match " + ID_PATTERN.pattern() + ": " + id);
        }
        return id;
    }

    public String id() {
        return id;
    }

    public UUID principalId() {
        return principalId;
    }

    public String currency() {
        return currency;
    }

    public UUID ledgerAccountId() {
        return ledgerAccountId;
    }

    public WalletStatus status() {
        return status;
    }

    /** Audit reason for the latest status change (null until the first freeze). */
    public String statusReason() {
        return statusReason;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Wallet wallet)) {
            return false;
        }
        return id.equals(wallet.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Wallet[" + id + " " + principalId + " " + currency + " " + status.wireName() + "]";
    }
}
