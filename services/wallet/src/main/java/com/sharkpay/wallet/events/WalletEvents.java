package com.sharkpay.wallet.events;

import com.sharkpay.wallet.api.dto.MoneyJson;
import com.sharkpay.wallet.domain.Balances;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Event factories for the wallet event types. Payload field names use
 * snake_case exactly as specified by the schemas (validated against
 * {@code additionalProperties: false} in WalletEventsTest):
 *
 * <ul>
 *   <li>{@code wallet.balance.changed.v1} — contracts/events/wallet.v1.json
 *       (pre-staged, read-only);</li>
 *   <li>{@code wallet.hold.placed/released/captured.v1} —
 *       contracts/events/wallet.holds.v1.json (this wave);</li>
 *   <li>{@code wallet.state.changed.v1} — contracts/events/wallet.state.v1.json
 *       (this wave).</li>
 * </ul>
 *
 * <p>{@code reason} and {@code from_status} are optional and omitted when
 * null (the mapper serializes with NON_NULL inclusion).
 */
public final class WalletEvents {

    /** Pre-staged schema: wallet.v1.json. */
    public static final String BALANCE_CHANGED = "wallet.balance.changed.v1";
    /** This wave's schema: wallet.holds.v1.json. */
    public static final String HOLD_PLACED = "wallet.hold.placed.v1";
    /** This wave's schema: wallet.holds.v1.json. */
    public static final String HOLD_RELEASED = "wallet.hold.released.v1";
    /** This wave's schema: wallet.holds.v1.json. */
    public static final String HOLD_CAPTURED = "wallet.hold.captured.v1";
    /** This wave's schema: wallet.state.v1.json. */
    public static final String STATE_CHANGED = "wallet.state.changed.v1";

    private WalletEvents() {
    }

    /** Builds the {@code wallet.hold.placed.v1} event. */
    public static CloudEvent holdPlaced(Wallet wallet, Hold hold, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), HOLD_PLACED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, hold.id(), occurredAt,
                new HoldEventData(hold.id(), hold.walletId(), wallet.principalId(),
                        wallet.currency(), MoneyJson.of(hold.amount()), hold.source(),
                        hold.sourceRef(), hold.reason(), HoldState.ACTIVE.wireName()));
    }

    /** Builds the {@code wallet.hold.released.v1} event. */
    public static CloudEvent holdReleased(Wallet wallet, Hold hold, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), HOLD_RELEASED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, hold.id(), occurredAt,
                new HoldEventData(hold.id(), hold.walletId(), wallet.principalId(),
                        wallet.currency(), MoneyJson.of(hold.amount()), hold.source(),
                        hold.sourceRef(), hold.reason(), HoldState.RELEASED.wireName()));
    }

    /**
     * Builds the {@code wallet.hold.captured.v1} event (terminal state with
     * the captured/released split).
     */
    public static CloudEvent holdCaptured(Wallet wallet, Hold hold, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), HOLD_CAPTURED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, hold.id(), occurredAt,
                new HoldCapturedData(hold.id(), hold.walletId(), wallet.principalId(),
                        wallet.currency(), MoneyJson.of(hold.amount()),
                        MoneyJson.of(hold.captured()), MoneyJson.of(hold.released()),
                        hold.source(), hold.sourceRef(), hold.reason(),
                        HoldState.CAPTURED.wireName()));
    }

    /**
     * Builds the {@code wallet.state.changed.v1} event. {@code from} is null
     * on wallet creation (the field is omitted).
     */
    public static CloudEvent walletStateChanged(Wallet wallet, WalletStatus from, String reason,
                                                Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), STATE_CHANGED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, wallet.id(), occurredAt,
                new WalletStateData(wallet.id(), wallet.principalId(), wallet.currency(),
                        from == null ? null : from.wireName(), wallet.status().wireName(), reason));
    }

    /**
     * Builds the {@code wallet.balance.changed.v1} event: the wallet's three
     * partitions after the change.
     */
    public static CloudEvent balanceChanged(Wallet wallet, Balances balances, Source source,
                                            UUID sourceRef, Instant occurredAt) {
        return new CloudEvent(EventIds.uuidV7().toString(), BALANCE_CHANGED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, wallet.id(), occurredAt,
                new WalletBalanceData(wallet.id(), wallet.principalId(), wallet.currency(),
                        new BalancesJson(MoneyJson.of(balances.available()),
                                MoneyJson.of(balances.pending()), MoneyJson.of(balances.held())),
                        source, sourceRef));
    }

    /** Payload of {@code wallet.hold.placed/released.v1} (schema: holdEventData). */
    public record HoldEventData(String hold_id, String wallet_id, UUID principal_id, String currency,
                                MoneyJson amount, Source source, UUID source_ref, String reason,
                                String state) {
    }

    /** Payload of {@code wallet.hold.captured.v1} (schema: holdCapturedData). */
    public record HoldCapturedData(String hold_id, String wallet_id, UUID principal_id,
                                   String currency, MoneyJson amount, MoneyJson captured_amount,
                                   MoneyJson released_amount, Source source, UUID source_ref,
                                   String reason, String state) {
    }

    /** Payload of {@code wallet.state.changed.v1} (schema: walletStateData). */
    public record WalletStateData(String wallet_id, UUID principal_id, String currency,
                                  String from_status, String to_status, String reason) {
    }

    /** Payload of {@code wallet.balance.changed.v1} (schema: walletBalanceData). */
    public record WalletBalanceData(String wallet_id, UUID principal_id, String currency,
                                    BalancesJson balances, Source source, UUID source_ref) {
    }

    /** Balance partitions, exactly as in wallet.v1.json $defs/balances. */
    public record BalancesJson(MoneyJson available, MoneyJson pending, MoneyJson held) {
    }
}
