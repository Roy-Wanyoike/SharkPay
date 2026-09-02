package com.sharkpay.gateway.api;

import com.sharkpay.gateway.domain.ApiKeyStateException;
import com.sharkpay.gateway.domain.DeliveryNotReplayableException;
import com.sharkpay.gateway.domain.DeliveryState;
import com.sharkpay.gateway.domain.GatewayDomainException;
import com.sharkpay.gateway.domain.HttpsUrlRequiredException;
import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.domain.InvalidEventTypesException;
import com.sharkpay.gateway.domain.QuotaExceededException;
import com.sharkpay.gateway.domain.SubscriptionStateException;
import com.sharkpay.gateway.domain.UnknownEventTypeException;
import com.sharkpay.gateway.domain.UnknownScopeException;
import com.sharkpay.gateway.events.EventIds;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.lang.reflect.Constructor;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global error handler: every domain error maps to the canonical error
 * envelope (common.yaml) with the right status, code, request id and
 * details — and 500s never leak their cause.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static com.sharkpay.gateway.api.dto.ErrorEnvelope body(
            ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response) {
        return response.getBody();
    }

    @Test
    void notFoundIs404() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.notFound(new NoSuchElementException("webhook endpoint wh_1 not found"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("not_found", body(response).error().code());
        assertEquals("webhook endpoint wh_1 not found", body(response).error().message());
        assertTrue(body(response).error().request_id().startsWith("req_"));
    }

    @Test
    void missingHeaderIs400WithTheHeaderNamed() throws Exception {
        Constructor<?> constructor = CreateDummy.class.getDeclaredConstructor(String.class);
        MissingRequestHeaderException missing = new MissingRequestHeaderException(
                "Idempotency-Key", new MethodParameter(constructor, 0));
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.missingHeader(missing);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("validation_error", body(response).error().code());
        assertEquals("Idempotency-Key", body(response).error().details().get("header"));
        assertTrue(body(response).error().message().contains("Idempotency-Key"));
    }

    @Test
    void invalidBodiesAre400WithFieldContext() throws Exception {
        Constructor<?> constructor = CreateDummy.class.getDeclaredConstructor(String.class);
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(new CreateDummy(""), "request");
        binding.rejectValue("scopes", "NotEmpty", "must not be empty");
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                new MethodParameter(constructor, 0), binding);

        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.invalidBody(invalid);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("validation_error", body(response).error().code());
        assertTrue(body(response).error().message().contains("scopes"),
                body(response).error().message());
    }

    /** Minimal bean for building a real binding result. */
    public static final class CreateDummy {
        private final String scopes;

        public CreateDummy(String scopes) {
            this.scopes = scopes;
        }

        public String getScopes() {
            return scopes;
        }
    }

    @Test
    void unreadableMessagesAndIllegalArgumentsAndUnknownScopesAre400() {
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException(
                "boom", new MockHttpInputMessage(new byte[0]));
        for (RuntimeException cause : new RuntimeException[]{unreadable,
                new IllegalArgumentException("limit must be between 1 and 100"),
                new UnknownScopeException("typo:scope")}) {
            ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                    handler.malformedRequest(cause);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("validation_error", body(response).error().code());
        }
    }

    @Test
    void idempotencyConflictsAre409() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.idempotencyConflict(new IdempotencyConflictException("idem-1"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("idempotency_conflict", body(response).error().code());
    }

    @Test
    void lifecycleConflictsAre409() {
        for (GatewayDomainException conflict : new GatewayDomainException[]{
                new ApiKeyStateException("only active keys can be rotated"),
                new SubscriptionStateException("endpoint is deleted"),
                new DeliveryNotReplayableException("whd_1", DeliveryState.DELIVERED)}) {
            ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                    handler.stateConflict(conflict);
            assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
            assertEquals("state_conflict", body(response).error().code());
            assertEquals(conflict.getMessage(), body(response).error().message());
        }
    }

    @Test
    void businessRuleRejectionsAre422WithTheirContractCodes() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> https =
                handler.httpsRequired(new HttpsUrlRequiredException("http://insecure"));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, https.getStatusCode());
        assertEquals("http_url_required", body(https).error().code());

        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> events =
                handler.invalidEvents(new InvalidEventTypesException("unknown event type"));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, events.getStatusCode());
        assertEquals("invalid_events", body(events).error().code());

        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> topic =
                handler.unknownEventType(new UnknownEventTypeException("some.topic.v1"));
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, topic.getStatusCode());
        assertEquals("unknown_event_type", body(topic).error().code());
    }

    @Test
    void quotaExceededIs429WithRetryAfterHeaderAndDetails() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.quotaExceeded(new QuotaExceededException(false, 42L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("quota_exceeded", body(response).error().code());
        assertEquals(42L, body(response).error().details().get("retry_after_seconds"));
        assertEquals("minute", body(response).error().details().get("window"));
        assertEquals("42", response.getHeaders().getFirst("Retry-After"));

        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> monthly =
                handler.quotaExceeded(new QuotaExceededException(true, 3600L));
        assertEquals("monthly", body(monthly).error().details().get("window"));
        assertEquals("3600", monthly.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void unexpectedErrorsAreSanitized500s() {
        // a domain exception that is NOT in the mapping table
        DomainExceptionWithoutMapping unlisted = new DomainExceptionWithoutMapping("boom");
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.domainError(unlisted);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("internal_error", body(response).error().code());
        assertEquals("An unexpected error occurred.", body(response).error().message());
        assertNotEquals("boom", body(response).error().message());

        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> unexpected =
                handler.unexpected(new RuntimeException("secret internals"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, unexpected.getStatusCode());
        assertEquals("internal_error", body(unexpected).error().code());
        assertEquals("An unexpected error occurred.", body(unexpected).error().message());
    }

    @Test
    void everyEnvelopeCarriesAFreshRequestId() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> first =
                handler.notFound(new NoSuchElementException("a"));
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> second =
                handler.notFound(new NoSuchElementException("b"));
        assertNotNull(body(first).error().request_id());
        assertNotEquals(body(first).error().request_id(), body(second).error().request_id());
        assertTrue(body(first).error().request_id().matches("^req_[0-9A-Za-z]+$"));
        // EventIds is the v7 generator used for events, unrelated to request ids
        assertNotNull(EventIds.uuidV7());
    }

    @Test
    void nullMessagesFallBackToTheStatusReasonPhrase() {
        ResponseEntity<com.sharkpay.gateway.api.dto.ErrorEnvelope> response =
                handler.notFound(new NoSuchElementException((String) null));
        assertEquals("not_found", body(response).error().code());
        assertEquals(HttpStatus.NOT_FOUND.getReasonPhrase(), body(response).error().message());
    }

    /** A domain exception with no dedicated mapping (500 path). */
    private static final class DomainExceptionWithoutMapping extends GatewayDomainException {
        DomainExceptionWithoutMapping(String message) {
            super(message);
        }
    }
}
