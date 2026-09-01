package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.ports.event.CloudEvent;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ChangePrincipalStatusUseCaseTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Nested
    class Transitions {

        @Test
        void suspendsAndReactivates() {
            Principal principal = harness.individual();

            Principal suspended = harness.changeStatus.execute(principal.id(), PrincipalStatus.SUSPENDED);
            assertThat(suspended.status()).isEqualTo(PrincipalStatus.SUSPENDED);

            Principal active = harness.changeStatus.execute(principal.id(), PrincipalStatus.ACTIVE);
            assertThat(active.status()).isEqualTo(PrincipalStatus.ACTIVE);
        }

        @Test
        void closesFromActiveAndSuspended() {
            Principal active = harness.individual();
            assertThat(harness.changeStatus.execute(active.id(), PrincipalStatus.CLOSED).status())
                    .isEqualTo(PrincipalStatus.CLOSED);

            Principal suspended = harness.suspendedWithTier(KycTier.UNVERIFIED);
            assertThat(harness.changeStatus.execute(suspended.id(), PrincipalStatus.CLOSED).status())
                    .isEqualTo(PrincipalStatus.CLOSED);
        }

        @Test
        void closedIsTerminal() {
            Principal closed = harness.closedIndividual();
            for (PrincipalStatus target : PrincipalStatus.values()) {
                assertThatThrownBy(() -> harness.changeStatus.execute(closed.id(), target))
                        .as("CLOSED -> %s must be rejected", target)
                        .isInstanceOf(ConflictException.class)
                        .extracting(e -> ((ConflictException) e).code())
                        .isEqualTo("ILLEGAL_STATUS_TRANSITION");
            }
        }

        @Test
        void sameStatusIsRejected() {
            Principal active = harness.individual();
            assertThatThrownBy(() -> harness.changeStatus.execute(active.id(), PrincipalStatus.ACTIVE))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        void unknownPrincipalIs404() {
            assertThatThrownBy(() -> harness.changeStatus.execute(
                    UUID.randomUUID(), PrincipalStatus.SUSPENDED))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }
    }

    @Nested
    class Events {

        @Test
        void publishesStatusChangedWithFromAndTo() {
            Principal principal = harness.individual();
            harness.changeStatus.execute(principal.id(), PrincipalStatus.SUSPENDED);

            CloudEvent event = harness.events.byType(IdentityEvents.PRINCIPAL_STATUS_CHANGED).get(0);
            assertThat(event.specversion()).isEqualTo("1.0");
            assertThat(event.source()).isEqualTo("sharkpay/identity");
            assertThat(event.time()).isEqualTo(harness.clock.now());
            assertThat(event.data())
                    .containsEntry("principal_id", principal.id().toString())
                    .containsEntry("shark_id", principal.sharkId().value())
                    .containsEntry("from_status", "ACTIVE")
                    .containsEntry("to_status", "SUSPENDED");
        }

        @Test
        void suspensionResetsTheTierAndPublishesTierChanged() {
            Principal principal = harness.individualWithTier(KycTier.LIMITED);

            Principal suspended = harness.changeStatus.execute(principal.id(), PrincipalStatus.SUSPENDED);

            assertThat(suspended.kycTier()).isEqualTo(KycTier.UNVERIFIED);
            assertThat(harness.principals.findById(principal.id()))
                    .hasValueSatisfying(p -> assertThat(p.kycTier()).isEqualTo(KycTier.UNVERIFIED));

            CloudEvent tierEvent = harness.events.byType(IdentityEvents.KYC_TIER_CHANGED).get(0);
            assertThat(tierEvent.data())
                    .containsEntry("principal_id", principal.id().toString())
                    .containsEntry("from_tier", "LIMITED")
                    .containsEntry("to_tier", "UNVERIFIED")
                    .containsEntry("reason", "suspension_reset")
                    .doesNotContainKey("provider_ref");
        }

        @Test
        void suspensionOfUnverifiedPrincipalEmitsNoTierEvent() {
            Principal principal = harness.individual();
            harness.changeStatus.execute(principal.id(), PrincipalStatus.SUSPENDED);

            assertThat(harness.events.byType(IdentityEvents.PRINCIPAL_STATUS_CHANGED)).hasSize(1);
            assertThat(harness.events.byType(IdentityEvents.KYC_TIER_CHANGED)).isEmpty();
        }

        @Test
        void closingEmitsStatusEventOnly() {
            Principal principal = harness.individualWithTier(KycTier.FULL);
            harness.changeStatus.execute(principal.id(), PrincipalStatus.CLOSED);

            assertThat(harness.events.byType(IdentityEvents.PRINCIPAL_STATUS_CHANGED)).hasSize(1);
            assertThat(harness.events.byType(IdentityEvents.KYC_TIER_CHANGED)).isEmpty();
            assertThat(harness.principals.findById(principal.id()))
                    .hasValueSatisfying(p -> {
                        assertThat(p.status()).isEqualTo(PrincipalStatus.CLOSED);
                        assertThat(p.kycTier()).isEqualTo(KycTier.FULL);
                    });
        }

        @Test
        void reactivationKeepsTheResetTier() {
            Principal principal = harness.individualWithTier(KycTier.LIMITED);
            harness.changeStatus.execute(principal.id(), PrincipalStatus.SUSPENDED);
            Principal reactivated = harness.changeStatus.execute(principal.id(), PrincipalStatus.ACTIVE);

            assertThat(reactivated.status()).isEqualTo(PrincipalStatus.ACTIVE);
            assertThat(reactivated.kycTier()).isEqualTo(KycTier.UNVERIFIED);
        }
    }
}
