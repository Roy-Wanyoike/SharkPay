package com.sharkpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrincipalTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:30Z");
    private static final OffsetDateTime T1 = T0.plusMinutes(5);

    private static Principal individual() {
        return new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0);
    }

    @Test
    void agentPrincipalsRequireAnOwner() {
        assertThatThrownBy(() -> new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.AGENT, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("AGENT_REQUIRES_OWNER");
    }

    @Test
    void nonAgentPrincipalsMustNotHaveAnOwner() {
        assertThatThrownBy(() -> new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, UUID.randomUUID(), PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("NON_AGENT_MUST_NOT_HAVE_OWNER");
    }

    @Test
    void requiredFieldsAreEnforced() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new Principal(null, SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Principal(id, null,
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Principal(id, SharkId.fromData("ABC123"),
                null, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Principal(id, SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, null, KycTier.UNVERIFIED, T0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Principal(id, SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, null, T0, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void createdAtMustNotBeAfterUpdatedAt() {
        assertThatThrownBy(() -> new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T1, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("INVALID_TIMESTAMPS");
    }

    @Test
    void withStatusAppliesLegalTransitions() {
        Principal active = individual();
        Principal suspended = active.withStatus(PrincipalStatus.SUSPENDED, T1);
        assertThat(suspended.status()).isEqualTo(PrincipalStatus.SUSPENDED);
        assertThat(suspended.updatedAt()).isEqualTo(T1);
        assertThat(suspended.createdAt()).isEqualTo(T0);
        assertThat(suspended.kycTier()).isEqualTo(KycTier.UNVERIFIED);

        Principal reactivated = suspended.withStatus(PrincipalStatus.ACTIVE, T1);
        assertThat(reactivated.status()).isEqualTo(PrincipalStatus.ACTIVE);

        assertThat(active.withStatus(PrincipalStatus.CLOSED, T1).status()).isEqualTo(PrincipalStatus.CLOSED);
        assertThat(suspended.withStatus(PrincipalStatus.CLOSED, T1).status()).isEqualTo(PrincipalStatus.CLOSED);
    }

    @Test
    void closedIsTerminal() {
        Principal closed = individual().withStatus(PrincipalStatus.CLOSED, T1);
        assertThatThrownBy(() -> closed.withStatus(PrincipalStatus.ACTIVE, T1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CLOSED is terminal")
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("ILLEGAL_STATUS_TRANSITION");
        assertThatThrownBy(() -> closed.withStatus(PrincipalStatus.SUSPENDED, T1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reEnteringTheSameStatusIsRejected() {
        assertThatThrownBy(() -> individual().withStatus(PrincipalStatus.ACTIVE, T1))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("ILLEGAL_STATUS_TRANSITION");
    }

    @Test
    void withStatusPreservesIdentityFields() {
        UUID ownerId = UUID.randomUUID();
        Principal agent = new Principal(UUID.randomUUID(), SharkId.fromData("ABC234"),
                PrincipalType.AGENT, ownerId, PrincipalStatus.ACTIVE, KycTier.LIMITED, T0, T0);
        Principal suspended = agent.withStatus(PrincipalStatus.SUSPENDED, T1);
        assertThat(suspended.id()).isEqualTo(agent.id());
        assertThat(suspended.sharkId()).isEqualTo(agent.sharkId());
        assertThat(suspended.ownerPrincipalId()).isEqualTo(ownerId);
        assertThat(suspended.kycTier()).isEqualTo(KycTier.LIMITED);
    }

    @Test
    void advanceKycTierFollowsTheLegalPath() {
        Principal unverified = individual();
        Principal limited = unverified.advanceKycTier(KycTier.LIMITED, T1);
        assertThat(limited.kycTier()).isEqualTo(KycTier.LIMITED);
        assertThat(limited.updatedAt()).isEqualTo(T1);

        Principal full = limited.advanceKycTier(KycTier.FULL, T1);
        assertThat(full.kycTier()).isEqualTo(KycTier.FULL);
    }

    @Test
    void illegalTierTransitionsAreRejected() {
        Principal unverified = individual();
        // skipping a tier
        assertThatThrownBy(() -> unverified.advanceKycTier(KycTier.FULL, T1))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("ILLEGAL_TIER_TRANSITION");
        // staying put
        assertThatThrownBy(() -> unverified.advanceKycTier(KycTier.UNVERIFIED, T1))
                .isInstanceOf(ConflictException.class);
        Principal full = unverified.advanceKycTier(KycTier.LIMITED, T1).advanceKycTier(KycTier.FULL, T1);
        // downgrades are never legal
        assertThatThrownBy(() -> full.advanceKycTier(KycTier.LIMITED, T1))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> full.advanceKycTier(KycTier.UNVERIFIED, T1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void tierResetRequiresSuspension() {
        Principal active = individual();
        assertThatThrownBy(() -> active.resetKycTier(T1))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("TIER_RESET_REQUIRES_SUSPENSION");
        Principal suspendedLimited = active.advanceKycTier(KycTier.LIMITED, T1)
                .withStatus(PrincipalStatus.SUSPENDED, T1);
        assertThat(suspendedLimited.resetKycTier(T1).kycTier()).isEqualTo(KycTier.UNVERIFIED);
    }

    @Test
    void statusTransitionMatrix() {
        assertThat(PrincipalStatus.CLOSED.isTerminal()).isTrue();
        assertThat(PrincipalStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(PrincipalStatus.SUSPENDED.isTerminal()).isFalse();

        assertThat(PrincipalStatus.ACTIVE.canTransitionTo(PrincipalStatus.SUSPENDED)).isTrue();
        assertThat(PrincipalStatus.ACTIVE.canTransitionTo(PrincipalStatus.CLOSED)).isTrue();
        assertThat(PrincipalStatus.ACTIVE.canTransitionTo(PrincipalStatus.ACTIVE)).isFalse();
        assertThat(PrincipalStatus.SUSPENDED.canTransitionTo(PrincipalStatus.ACTIVE)).isTrue();
        assertThat(PrincipalStatus.SUSPENDED.canTransitionTo(PrincipalStatus.CLOSED)).isTrue();
        assertThat(PrincipalStatus.SUSPENDED.canTransitionTo(PrincipalStatus.SUSPENDED)).isFalse();
        assertThat(PrincipalStatus.CLOSED.canTransitionTo(PrincipalStatus.ACTIVE)).isFalse();
        assertThat(PrincipalStatus.CLOSED.canTransitionTo(PrincipalStatus.SUSPENDED)).isFalse();
        assertThat(PrincipalStatus.CLOSED.canTransitionTo(PrincipalStatus.CLOSED)).isFalse();
    }

    @Test
    void tierMatrixAndRank() {
        assertThat(KycTier.UNVERIFIED.canAdvanceTo(KycTier.LIMITED)).isTrue();
        assertThat(KycTier.UNVERIFIED.canAdvanceTo(KycTier.FULL)).isFalse();
        assertThat(KycTier.UNVERIFIED.canAdvanceTo(KycTier.UNVERIFIED)).isFalse();
        assertThat(KycTier.LIMITED.canAdvanceTo(KycTier.FULL)).isTrue();
        assertThat(KycTier.LIMITED.canAdvanceTo(KycTier.LIMITED)).isFalse();
        assertThat(KycTier.LIMITED.canAdvanceTo(KycTier.UNVERIFIED)).isFalse();
        assertThat(KycTier.FULL.canAdvanceTo(KycTier.UNVERIFIED)).isFalse();
        assertThat(KycTier.FULL.canAdvanceTo(KycTier.LIMITED)).isFalse();

        assertThat(KycTier.UNVERIFIED.rank()).isLessThan(KycTier.LIMITED.rank());
        assertThat(KycTier.LIMITED.rank()).isLessThan(KycTier.FULL.rank());
    }

    @Test
    void principalIsAValueCarryingRecord() {
        UUID id = UUID.randomUUID();
        SharkId sharkId = SharkId.fromData("ABC123");
        Principal principal = new Principal(id, sharkId, PrincipalType.BUSINESS, null,
                PrincipalStatus.ACTIVE, KycTier.FULL, T0, T0);
        assertThat(principal.id()).isEqualTo(id);
        assertThat(principal.sharkId()).isEqualTo(sharkId);
        assertThat(principal.type()).isEqualTo(PrincipalType.BUSINESS);
        assertThat(principal.status()).isEqualTo(PrincipalStatus.ACTIVE);
        assertThat(principal.kycTier()).isEqualTo(KycTier.FULL);
    }
}
