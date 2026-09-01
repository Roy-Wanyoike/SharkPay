package com.sharkpay.identity.fakes;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.EventPublisher;
import com.sharkpay.identity.ports.IdempotencyStore;
import com.sharkpay.identity.ports.PrincipalRepository;
import com.sharkpay.identity.ports.Randomness;
import com.sharkpay.identity.service.ChangePrincipalStatusUseCase;
import com.sharkpay.identity.service.CreateAgentUseCase;
import com.sharkpay.identity.service.CreatePrincipalUseCase;
import com.sharkpay.identity.service.GetPrincipalUseCase;
import com.sharkpay.identity.service.ListDevicesUseCase;
import com.sharkpay.identity.service.RegisterDeviceUseCase;
import com.sharkpay.identity.service.RevokeDeviceUseCase;
import com.sharkpay.identity.service.SharkIdGenerator;
import com.sharkpay.identity.service.VerifyKycUseCase;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Assembles the whole hexagon on fakes: the same wiring as
 * {@link com.sharkpay.identity.config.IdentityConfig} but with deterministic
 * ports. Shared by service and controller tests.
 */
public final class IdentityHarness {

    public static final OffsetDateTime INITIAL_TIME = OffsetDateTime.parse("2026-09-01T10:00:30Z");

    public final InMemoryPrincipalRepository principals = new InMemoryPrincipalRepository();
    public final InMemoryKycRepository kycRecords = new InMemoryKycRepository();
    public final InMemoryDeviceRepository devices = new InMemoryDeviceRepository();
    public final InMemoryIdempotencyStore idempotency = new InMemoryIdempotencyStore();
    public final RecordingEventPublisher events = new RecordingEventPublisher();
    public final FixedClock clock = new FixedClock(INITIAL_TIME);
    public final ScriptedRandomness randomness = new ScriptedRandomness();

    public final SharkIdGenerator sharkIdGenerator = new SharkIdGenerator(principals, randomness);
    public final CreatePrincipalUseCase createPrincipal =
            new CreatePrincipalUseCase(principals, sharkIdGenerator, events, idempotency, clock);
    public final CreateAgentUseCase createAgent = new CreateAgentUseCase(createPrincipal);
    public final GetPrincipalUseCase getPrincipal = new GetPrincipalUseCase(principals);
    public final ChangePrincipalStatusUseCase changeStatus =
            new ChangePrincipalStatusUseCase(principals, events, clock);
    public final VerifyKycUseCase verifyKyc =
            new VerifyKycUseCase(principals, kycRecords, events, clock);
    public final RegisterDeviceUseCase registerDevice =
            new RegisterDeviceUseCase(principals, devices, clock);
    public final ListDevicesUseCase listDevices = new ListDevicesUseCase(principals, devices);
    public final RevokeDeviceUseCase revokeDevice = new RevokeDeviceUseCase(principals, devices);

    public PrincipalRepository principalRepo() {
        return principals;
    }

    public EventPublisher eventPublisher() {
        return events;
    }

    public IdempotencyStore idempotencyStore() {
        return idempotency;
    }

    public Clock clockPort() {
        return clock;
    }

    public Randomness randomnessPort() {
        return randomness;
    }

    /** Creates and stores a fresh INDIVIDUAL principal (ACTIVE, UNVERIFIED). */
    public Principal individual() {
        return principal(PrincipalType.INDIVIDUAL, null);
    }

    /** Creates and stores a fresh principal of the given type. */
    public Principal principal(PrincipalType type, Principal owner) {
        OffsetDateTime now = clock.now();
        Principal principal = new Principal(
                UUID.randomUUID(),
                sharkIdGenerator.generate(),
                type,
                owner == null ? null : owner.id(),
                PrincipalStatus.ACTIVE,
                KycTier.UNVERIFIED,
                now,
                now);
        return principals.save(principal);
    }

    /** Creates a principal and steps its tier forward to {@code tier}. */
    public Principal individualWithTier(KycTier tier) {
        Principal principal = individual();
        while (principal.kycTier() != tier) {
            principal = principals.save(principal.advanceKycTier(nextTier(principal.kycTier()), clock.now()));
        }
        return principal;
    }

    private static KycTier nextTier(KycTier current) {
        return switch (current) {
            case UNVERIFIED -> KycTier.LIMITED;
            case LIMITED -> KycTier.FULL;
            case FULL -> throw new IllegalArgumentException("already FULL");
        };
    }

    /**
     * Creates a SUSPENDED principal whose tier is still {@code tier}
     * (the tier reset is what the status use-case under test will perform).
     */
    public Principal suspendedWithTier(KycTier tier) {
        Principal principal = individualWithTier(tier);
        return principals.save(principal.withStatus(PrincipalStatus.SUSPENDED, clock.now()));
    }

    public Principal closedIndividual() {
        Principal principal = individual();
        return principals.save(principal.withStatus(PrincipalStatus.CLOSED, clock.now()));
    }

    /** A syntactically valid, checksum-correct SharkId (not necessarily stored). */
    public static SharkId validSharkId(String data6) {
        return SharkId.fromData(data6);
    }
}
