package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.ports.LedgerAccounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory LedgerAccounts fake (src/test, per ADR 003): provisions ledger accounts with
 * deterministic ids (UUID v3 of {@code wallet:<principal>:<currency>}), so
 * the same pair always maps to the same account — mirroring the real
 * adapter's idempotency (the ledger dedupes on the account code). Tests use
 * {@link #accountId(UUID, String)} to build ledger events that target a
 * wallet's account.
 */
public final class FakeLedgerAccounts implements LedgerAccounts {

    private final Map<String, UUID> accountsByPair = new ConcurrentHashMap<>();
    private final List<Provision> provisioningLog = new ArrayList<>();

    /** The deterministic account id for a principal-currency pair. */
    public static UUID accountId(UUID principalId, String currency) {
        String name = "wallet:" + principalId + ":" + currency.toUpperCase();
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    @Override
    public UUID provisionWalletAccount(UUID principalId, String currency) {
        UUID accountId = accountId(principalId, currency);
        accountsByPair.putIfAbsent(pairKey(principalId, currency), accountId);
        provisioningLog.add(new Provision(principalId, currency.toUpperCase(), accountId));
        return accountId;
    }

    /** All provisioning calls, in order (test assertions). */
    public List<Provision> provisioningLog() {
        return List.copyOf(provisioningLog);
    }

    /** Number of distinct provisioned accounts. */
    public int distinctAccounts() {
        return accountsByPair.size();
    }

    private static String pairKey(UUID principalId, String currency) {
        return principalId + "|" + currency.toUpperCase();
    }

    /** One provisioning call (audit record). */
    public record Provision(UUID principalId, String currency, UUID accountId) {
    }
}
