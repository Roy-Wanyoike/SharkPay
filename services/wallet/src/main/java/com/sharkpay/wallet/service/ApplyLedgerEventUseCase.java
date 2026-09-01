package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.ProjectionInconsistencyException;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ledger.LedgerPostingEvent;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.LedgerEventConsumer;
import com.sharkpay.wallet.ports.ProjectionStore;
import com.sharkpay.wallet.ports.WalletRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The ledger balance projection: consumes
 * {@code ledger.posting.committed.v1} events (this use-case implements the
 * {@link LedgerEventConsumer} port; the real NATS/Kafka binding and the dev
 * HTTP endpoint call it) and updates wallet total balances.
 *
 * <p><b>Consistency under at-least-once, out-of-order delivery.</b>
 * <ol>
 *   <li>Event-id dedup: an already-applied event id returns immediately
 *       (cheap fast path).</li>
 *   <li>For every leg whose account is a known wallet, the leg is applied
 *       to the projection keyed by the ledger's globally monotonic
 *       {@code posting_id}; an already-present (wallet, posting) pair is a
 *       no-op — duplicates can never double-apply. Totals and running
 *       {@code balance_after} values are recomputed in posting-id order, so
 *       out-of-order arrival converges to the in-order projection.</li>
 *   <li>The event id is marked applied only after its legs were processed —
 *       a crash mid-application is repaired by redelivery (remaining legs
 *       applied, present ones skipped).</li>
 *   <li>{@code wallet.balance.changed.v1} is published once per wallet whose
 *       projection actually changed (source/source_ref propagate from the
 *       ledger entry).</li>
 * </ol>
 *
 * <p><b>Defensive money checks</b> (the ledger guarantees these upstream, so
 * a violation is a contract breach and the event is rejected whole for
 * dead-lettering): legs of a wallet account must carry the wallet's
 * currency, the running balance must never go below zero and must never
 * overflow int64 minor units.
 */
public final class ApplyLedgerEventUseCase implements LedgerEventConsumer {

    private final WalletRepository wallets;
    private final ProjectionStore projections;
    private final BalanceReader balances;
    private final EventPublisher events;

    public ApplyLedgerEventUseCase(WalletRepository wallets, ProjectionStore projections,
                                   BalanceReader balances, EventPublisher events) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.projections = Objects.requireNonNull(projections, "projectionStore is required");
        this.balances = Objects.requireNonNull(balances, "balanceReader is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
    }

    @Override
    public void onLedgerPosting(LedgerPostingEvent event) {
        Objects.requireNonNull(event, "event is required");
        event.validate();

        if (projections.isEventApplied(event.id())) {
            return;   // exact redelivery: nothing to do
        }

        // walletId → number of newly applied legs, in first-touch order
        Map<String, Integer> appliedLegsByWallet = new LinkedHashMap<>();
        for (LedgerPostingEvent.Posting posting : event.data().postings()) {
            Wallet wallet = wallets.findByLedgerAccountId(posting.account_id()).orElse(null);
            if (wallet == null) {
                continue;   // not one of our wallet accounts (clearing, fees, fx, ...)
            }
            if (posting.currency() != null && !posting.currency().equals(wallet.currency())) {
                throw new ProjectionInconsistencyException("leg " + posting.posting_id()
                        + " targets account " + posting.account_id() + " (wallet " + wallet.id()
                        + ", " + wallet.currency() + ") with currency " + posting.currency());
            }
            Money amount = Money.of(posting.amountMinor(), wallet.currency());
            ProjectionLeg leg = new ProjectionLeg(posting.posting_id(), event.data().entry_id(),
                    event.data().entry_type(), posting.direction(), amount,
                    event.data().source(), event.data().source_ref(),
                    event.data().reason(), event.occurred_at());
            if (projections.applyLeg(wallet.id(), leg)) {
                appliedLegsByWallet.merge(wallet.id(), 1, Integer::sum);
            }
        }

        projections.markEventApplied(event.id(), event.data().entry_id());

        appliedLegsByWallet.forEach((walletId, newlyAppliedLegs) -> {
            Wallet wallet = wallets.findById(walletId).orElseThrow(() -> new ProjectionInconsistencyException(
                    "wallet " + walletId + " disappeared while projecting event " + event.id()));
            events.publish(WalletEvents.balanceChanged(wallet, balances.balancesOf(wallet),
                    event.data().source(), event.data().source_ref(), event.occurred_at()));
        });
    }
}
