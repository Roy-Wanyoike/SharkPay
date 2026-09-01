package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.EventPublisher;
import com.sharkpay.identity.ports.IdempotencyStore;
import com.sharkpay.identity.ports.IdempotentRequest;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates principals (individual / business / agent) with a generated
 * SharkId, honoring {@code Idempotency-Key} semantics: replay of the same
 * key with the same canonical body returns the original principal; the same
 * key with a different body is a conflict.
 */
public final class CreatePrincipalUseCase {

    /** Input: principal type, optional owner SharkId, optional idempotency key. */
    public record Command(PrincipalType type, SharkId ownerSharkId, String idempotencyKey) {
    }

    /** Outcome of the use-case, consumed by the API layer for 201/200/409. */
    public sealed interface Result permits Created, Replayed, Conflict {
    }

    public record Created(Principal principal) implements Result {
    }

    public record Replayed(Principal principal) implements Result {
    }

    public record Conflict(String idempotencyKey) implements Result {
    }

    private final PrincipalRepository principalRepository;
    private final SharkIdGenerator sharkIdGenerator;
    private final EventPublisher eventPublisher;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public CreatePrincipalUseCase(PrincipalRepository principalRepository,
                                  SharkIdGenerator sharkIdGenerator,
                                  EventPublisher eventPublisher,
                                  IdempotencyStore idempotencyStore,
                                  Clock clock) {
        this.principalRepository = principalRepository;
        this.sharkIdGenerator = sharkIdGenerator;
        this.eventPublisher = eventPublisher;
        this.idempotencyStore = idempotencyStore;
        this.clock = clock;
    }

    public Result execute(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        String key = command.idempotencyKey();
        if (key != null) {
            Optional<IdempotentRequest> stored = idempotencyStore.findByKey(key);
            if (stored.isPresent()) {
                return replay(command, key, stored.get());
            }
        }
        UUID ownerPrincipalId = resolveOwner(command);
        OffsetDateTime now = clock.now();
        Principal principal = new Principal(
                UUID.randomUUID(),
                sharkIdGenerator.generate(),
                command.type(),
                ownerPrincipalId,
                PrincipalStatus.ACTIVE,
                KycTier.UNVERIFIED,
                now,
                now);
        Principal saved = principalRepository.save(principal);
        if (key != null) {
            idempotencyStore.save(new IdempotentRequest(
                    key, RequestFingerprint.ofCreatePrincipal(command), saved.id()));
        }
        eventPublisher.publish(IdentityEvents.principalCreated(saved, now));
        return new Created(saved);
    }

    private Result replay(Command command, String key, IdempotentRequest stored) {
        if (!stored.requestFingerprint().equals(RequestFingerprint.ofCreatePrincipal(command))) {
            return new Conflict(key);
        }
        Principal original = principalRepository.findById(stored.principalId())
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "idempotency key '" + key + "' points at principal " + stored.principalId()
                                + " which no longer exists"));
        return new Replayed(original);
    }

    /**
     * Owner rules: only AGENT principals reference an owner; the owner must
     * exist, be INDIVIDUAL or BUSINESS, and be ACTIVE.
     */
    private UUID resolveOwner(Command command) {
        if (command.type() != PrincipalType.AGENT) {
            if (command.ownerSharkId() != null) {
                throw new ValidationException("OWNER_NOT_ALLOWED",
                        "owner_shark_id may only be supplied for AGENT principals");
            }
            return null;
        }
        if (command.ownerSharkId() == null) {
            throw new ValidationException("OWNER_REQUIRED",
                    "an AGENT principal requires owner_shark_id");
        }
        Principal owner = principalRepository.findBySharkId(command.ownerSharkId())
                .orElseThrow(() -> new ValidationException("OWNER_NOT_FOUND",
                        "no principal with shark id " + command.ownerSharkId().value()));
        if (owner.type() == PrincipalType.AGENT) {
            throw new ValidationException("OWNER_NOT_ELIGIBLE",
                    "an AGENT principal cannot own other agents");
        }
        if (owner.status() != PrincipalStatus.ACTIVE) {
            throw new ValidationException("OWNER_NOT_ELIGIBLE",
                    "owner " + command.ownerSharkId().value() + " is " + owner.status()
                            + "; owners must be ACTIVE");
        }
        return owner.id();
    }
}
