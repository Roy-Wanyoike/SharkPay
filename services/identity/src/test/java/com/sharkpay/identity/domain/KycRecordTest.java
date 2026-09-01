package com.sharkpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KycRecordTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:30Z");

    @Test
    void pendingDecisionCarriesNoDecisionTime() {
        KycRecord record = new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, "prov-1", null, T0);
        assertThat(record.decidedAt()).isNull();
        assertThat(record.status()).isEqualTo(KycStatus.PENDING);
        assertThat(record.tier()).isEqualTo(KycTier.LIMITED);
        assertThat(record.providerRef()).isEqualTo("prov-1");
    }

    @Test
    void approvedAndRejectedDecisionsRequireADecisionTime() {
        UUID id = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        assertThatCode(() -> new KycRecord(id, principalId, KycTier.LIMITED,
                KycStatus.APPROVED, null, T0, T0)).doesNotThrowAnyException();
        assertThatCode(() -> new KycRecord(id, principalId, KycTier.LIMITED,
                KycStatus.REJECTED, null, T0, T0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new KycRecord(id, principalId, KycTier.LIMITED,
                KycStatus.APPROVED, null, null, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("DECIDED_REQUIRES_TIME");
        assertThatThrownBy(() -> new KycRecord(id, principalId, KycTier.LIMITED,
                KycStatus.REJECTED, null, null, T0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void pendingDecisionMustNotCarryADecisionTime() {
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, null, T0, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("PENDING_HAS_NO_DECISION_TIME");
    }

    @Test
    void providerRefMustNotBeBlankWhenPresent() {
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, "   ", null, T0))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("INVALID_PROVIDER_REF");
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> new KycRecord(null, UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), null,
                KycTier.LIMITED, KycStatus.PENDING, null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                null, KycStatus.PENDING, null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, null, null, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
