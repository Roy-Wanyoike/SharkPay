package com.sharkpay.wallet.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key of the {@code wallet_postings} projection table:
 * (wallet_id, posting_id) — the leg-level dedup that makes duplicate ledger
 * event delivery a no-op.
 */
@Embeddable
public class WalletPostingId implements Serializable {

    @Column(name = "wallet_id", nullable = false, length = 40)
    private String walletId;

    @Column(name = "posting_id", nullable = false)
    private long postingId;

    protected WalletPostingId() {
    }

    public WalletPostingId(String walletId, long postingId) {
        this.walletId = Objects.requireNonNull(walletId, "walletId is required");
        this.postingId = postingId;
    }

    public String getWalletId() {
        return walletId;
    }

    public long getPostingId() {
        return postingId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WalletPostingId that)) {
            return false;
        }
        return postingId == that.postingId && walletId.equals(that.walletId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(walletId, postingId);
    }
}
