package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.ports.event.CloudEvent;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VerifyKycUseCaseTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Nested
    class LegalTransitions {

        @Test
        void advancesOneStepAtATimeToFull() {
            Principal principal = harness.individual();

            harness.clock.advanceBy(java.time.Duration.ofMinutes(1));
            VerifyKycUseCase.Result limited = harness.verifyKyc.execute(
                    principal.id(), KycTier.LIMITED, KycStatus.APPROVED, "provider/ref-1");
            assertThat(limited.principal().kycTier()).isEqualTo(KycTier.LIMITED);

            harness.clock.advanceBy(java.time.Duration.ofMinutes(1));
            VerifyKycUseCase.Result full = harness.verifyKyc.execute(
                    principal.id(), KycTier.FULL, KycStatus.APPROVED, "provider/ref-2");
            assertThat(full.principal().kycTier()).isEqualTo(KycTier.FULL);

            assertThat(harness.kycRecords.all()).hasSize(2);
            assertThat(harness.events.byType(IdentityEvents.KYC_TIER_CHANGED)).hasSize(2);
        }

        @Test
        void reVerificationAfterSuspensionResetWorks() {
            Principal principal = harness.individualWithTier(KycTier.FULL);
            Principal suspended = harness.principals.save(
                    principal.withStatus(com.sharkpay.identity.domain.PrincipalStatus.SUSPENDED,
                            harness.clock.now()));
            // the status use-case would have reset the tier; domain reset:
            Principal reset = harness.principals.save(suspended.resetKycTier(harness.clock.now()));

            VerifyKycUseCase.Result reLimited = harness.verifyKyc.execute(
                    reset.id(), KycTier.LIMITED, KycStatus.APPROVED, null);
            assertThat(reLimited.principal().kycTier()).isEqualTo(KycTier.LIMITED);
        }
    }

    @Nested
    class IllegalTransitions {

        @Test
        void skippingATierIsRejected() {
            Principal principal = harness.individual();
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    principal.id(), KycTier.FULL, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("ILLEGAL_TIER_TRANSITION");
            assertThat(harness.kycRecords.all()).isEmpty();
            assertThat(harness.events.events()).isEmpty();
        }

        @Test
        void downgradesAndNoOpsAreRejected() {
            Principal full = harness.individualWithTier(KycTier.FULL);
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    full.id(), KycTier.LIMITED, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("ILLEGAL_TIER_TRANSITION");
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    full.id(), KycTier.FULL, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    full.id(), KycTier.UNVERIFIED, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(ConflictException.class);
            assertThat(harness.kycRecords.all()).isEmpty();
        }

        @Test
        void closedPrincipalsAcceptNoDecisions() {
            Principal closed = harness.closedIndividual();
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    closed.id(), KycTier.LIMITED, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("PRINCIPAL_CLOSED");
        }

        @Test
        void unknownPrincipalIs404() {
            assertThatThrownBy(() -> harness.verifyKyc.execute(
                    UUID.randomUUID(), KycTier.LIMITED, KycStatus.APPROVED, "ref"))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }
    }

    @Nested
    class DecisionRecords {

        @Test
        void pendingDecisionRecordsWithoutTierChange() {
            Principal principal = harness.individual();
            VerifyKycUseCase.Result result = harness.verifyKyc.execute(
                    principal.id(), KycTier.LIMITED, KycStatus.PENDING, "provider/ref");

            assertThat(result.principal().kycTier()).isEqualTo(KycTier.UNVERIFIED);
            assertThat(result.record().status()).isEqualTo(KycStatus.PENDING);
            assertThat(result.record().decidedAt()).isNull();
            assertThat(result.record().principalId()).isEqualTo(principal.id());
            assertThat(result.record().providerRef()).isEqualTo("provider/ref");
            assertThat(harness.events.events()).isEmpty();
        }

        @Test
        void rejectedDecisionRecordsWithoutTierChange() {
            Principal principal = harness.individual();
            VerifyKycUseCase.Result result = harness.verifyKyc.execute(
                    principal.id(), KycTier.FULL, KycStatus.REJECTED, null);

            assertThat(result.principal().kycTier()).isEqualTo(KycTier.UNVERIFIED);
            assertThat(result.record().status()).isEqualTo(KycStatus.REJECTED);
            assertThat(result.record().decidedAt()).isEqualTo(harness.clock.now());
            assertThat(result.record().providerRef()).isNull();
            assertThat(harness.events.events()).isEmpty();
        }
    }

    @Nested
    class Events {

        @Test
        void tierChangedEventCarriesFromToAndProviderRef() {
            Principal principal = harness.individual();
            harness.verifyKyc.execute(principal.id(), KycTier.LIMITED, KycStatus.APPROVED, "prov-77");

            CloudEvent event = harness.events.last();
            assertThat(event.type()).isEqualTo("identity.kyc.tier.changed.v1");
            assertThat(event.specversion()).isEqualTo("1.0");
            assertThat(event.source()).isEqualTo("sharkpay/identity");
            assertThat(event.time()).isEqualTo(harness.clock.now());
            assertThat(event.data())
                    .containsEntry("principal_id", principal.id().toString())
                    .containsEntry("shark_id", principal.sharkId().value())
                    .containsEntry("from_tier", "UNVERIFIED")
                    .containsEntry("to_tier", "LIMITED")
                    .containsEntry("reason", "kyc_decision")
                    .containsEntry("provider_ref", "prov-77");
        }

        @Test
        void storedPrincipalReflectsTheNewTier() {
            Principal principal = harness.individual();
            harness.verifyKyc.execute(principal.id(), KycTier.LIMITED, KycStatus.APPROVED, null);
            assertThat(harness.principals.findById(principal.id()))
                    .hasValueSatisfying(p -> assertThat(p.kycTier()).isEqualTo(KycTier.LIMITED));
        }
    }
}
