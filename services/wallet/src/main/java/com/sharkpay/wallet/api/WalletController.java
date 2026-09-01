package com.sharkpay.wallet.api;

import com.sharkpay.money.Currencies;
import com.sharkpay.wallet.api.dto.StatementEntryJson;
import com.sharkpay.wallet.api.dto.StatementListJson;
import com.sharkpay.wallet.api.dto.WalletJson;
import com.sharkpay.wallet.api.dto.WalletListJson;
import com.sharkpay.wallet.domain.WalletStatus;
import com.sharkpay.wallet.service.GetStatementUseCase;
import com.sharkpay.wallet.service.GetWalletUseCase;
import com.sharkpay.wallet.service.ListWalletsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public read-side adapter implementing contracts/openapi/v1/wallets.yaml:
 * list wallets, read a wallet with its balance partitions, and read the
 * cursor-paginated ledger statement of a wallet. Mutating operations are
 * internal-only (see the Internal* controllers).
 */
@RestController
public final class WalletController {

    private final GetWalletUseCase getWallet;
    private final ListWalletsUseCase listWallets;
    private final GetStatementUseCase statement;

    public WalletController(GetWalletUseCase getWallet, ListWalletsUseCase listWallets,
                            GetStatementUseCase statement) {
        this.getWallet = getWallet;
        this.listWallets = listWallets;
        this.statement = statement;
    }

    /** listWallets: filter by principal / currency / status, cursor-paginated. */
    @GetMapping("/wallets")
    public WalletListJson list(@RequestParam(required = false) UUID principal_id,
                               @RequestParam(required = false) String currency,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String cursor) {
        String canonicalCurrency = currency == null || currency.isBlank() ? null
                : Currencies.normalize(currency);
        WalletStatus statusFilter = status == null || status.isBlank() ? null
                : WalletStatus.fromWire(status);
        ListWalletsUseCase.Result result = listWallets.list(principal_id, canonicalCurrency,
                statusFilter, limit, cursor);
        return new WalletListJson(result.items().stream()
                .map(item -> WalletJson.of(item.wallet(), item.balances()))
                .toList(), result.nextCursor());
    }

    /** getWallet: the wallet with its balance partitions. */
    @GetMapping("/wallets/{id}")
    public WalletJson get(@PathVariable("id") String id) {
        GetWalletUseCase.WalletWithBalances result = getWallet.get(id);
        return WalletJson.of(result.wallet(), result.balances());
    }

    /** getWalletStatement: projection lines in ledger order. */
    @GetMapping("/wallets/{id}/statement")
    public StatementListJson getStatement(@PathVariable("id") String id,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        GetStatementUseCase.Result result = statement.statement(id, limit, cursor);
        return new StatementListJson(result.items().stream()
                .map(StatementEntryJson::of)
                .toList(), result.nextCursor());
    }
}
