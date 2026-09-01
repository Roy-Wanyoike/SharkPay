package com.sharkpay.fx.api;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.service.ConvertUseCase;
import com.sharkpay.fx.service.CreateQuoteUseCase;
import com.sharkpay.fx.service.LockQuoteUseCase;
import com.sharkpay.fx.ports.ConversionRepository;
import com.sharkpay.fx.ports.QuoteRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * REST adapter implementing contracts/openapi/v1/fx.yaml:
 * create quote, get quote, lock quote, convert, get conversion.
 *
 * <ul>
 *   <li>Money JSON is canonical: {@code {amount_minor, currency, exponent}}.</li>
 *   <li>{@code POST /fx/quotes} → 201; business rejections → 422
 *       (same_currency, unsupported_currency_pair); malformed → 400.</li>
 *   <li>{@code POST /fx/quotes/{id}/lock} → 200 (idempotent); state/expiry
 *       conflicts → 409; unknown id → 404.</li>
 *   <li>{@code POST /fx/convert} → 201 with the ledger entry id; replayed
 *       idempotent requests get {@code X-Idempotent-Replay: true}; state
 *       conflicts → 409; payload mismatch on the same key → 409
 *       idempotency_conflict; unknown quote → 404.</li>
 * </ul>
 */
@RestController
@RequestMapping("/fx")
public final class FxController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final CreateQuoteUseCase createQuote;
    private final LockQuoteUseCase lockQuote;
    private final ConvertUseCase convert;
    private final QuoteRepository quotes;
    private final ConversionRepository conversions;

    public FxController(CreateQuoteUseCase createQuote, LockQuoteUseCase lockQuote, ConvertUseCase convert,
                        QuoteRepository quotes, ConversionRepository conversions) {
        this.createQuote = createQuote;
        this.lockQuote = lockQuote;
        this.convert = convert;
        this.quotes = quotes;
        this.conversions = conversions;
    }

    @PostMapping("/quotes")
    public ResponseEntity<QuoteJson> createQuote(@Valid @RequestBody QuoteCreateRequest request) {
        CreateQuoteUseCase.CreateQuoteResult result = createQuote.create(
                request.amount_minor(), request.base_currency(), request.quote_currency(),
                request.expires_in_seconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(QuoteJson.of(result));
    }

    @GetMapping("/quotes/{quoteId}")
    public QuoteJson getQuote(@PathVariable String quoteId) {
        return QuoteJson.of(loadQuote(quoteId));
    }

    @PostMapping("/quotes/{quoteId}/lock")
    public ResponseEntity<QuoteJson> lockQuote(@PathVariable String quoteId) {
        LockQuoteUseCase.LockResult result = lockQuote.lock(quoteId);
        return ResponseEntity.ok(QuoteJson.of(result.quote()));
    }

    @PostMapping("/convert")
    public ResponseEntity<ConversionJson> convert(@RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                  @Valid @RequestBody ConversionCreateRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        ConvertUseCase.Result result = convert.convert(idempotencyKey, request.quote_id(),
                request.source_wallet(), request.destination_wallet());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replay()) {
            response.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(ConversionJson.of(result.conversion()));
    }

    @GetMapping("/conversions/{conversionId}")
    public ConversionJson getConversion(@PathVariable String conversionId) {
        return ConversionJson.of(loadConversion(conversionId));
    }

    private com.sharkpay.fx.domain.Quote loadQuote(String quoteId) {
        return quotes.findById(quoteId)
                .orElseThrow(() -> new NoSuchElementException("quote " + quoteId + " not found"));
    }

    private Conversion loadConversion(String conversionId) {
        return conversions.findById(conversionId)
                .orElseThrow(() -> new NoSuchElementException("conversion " + conversionId + " not found"));
    }
}
