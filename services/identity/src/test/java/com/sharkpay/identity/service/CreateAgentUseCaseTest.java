package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.fakes.IdentityHarness;
import org.junit.jupiter.api.Test;

class CreateAgentUseCaseTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Test
    void createsAgentOwnedByAnActiveIndividualOrBusiness() {
        Principal individualOwner = harness.individual();
        Principal businessOwner = harness.principal(PrincipalType.BUSINESS, null);

        Principal agentOfIndividual = created(individualOwner.sharkId());
        Principal agentOfBusiness = created(businessOwner.sharkId());

        assertThat(agentOfIndividual.type()).isEqualTo(PrincipalType.AGENT);
        assertThat(agentOfIndividual.ownerPrincipalId()).isEqualTo(individualOwner.id());
        assertThat(agentOfBusiness.ownerPrincipalId()).isEqualTo(businessOwner.id());
        // owners are seeded directly (no events); only the two agent creations
        // go through the use case and publish identity.principal.created.v1
        assertThat(harness.events.byType(IdentityEvents.PRINCIPAL_CREATED)).hasSize(2);
    }

    @Test
    void rejectsUnknownOwner() {
        assertThatThrownBy(() -> harness.createAgent.execute(SharkId.fromData("QQQQQQ"), null))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("OWNER_NOT_FOUND");
    }

    @Test
    void rejectsInactiveOwner() {
        Principal suspended = harness.suspendedWithTier(com.sharkpay.identity.domain.KycTier.UNVERIFIED);
        assertThatThrownBy(() -> harness.createAgent.execute(suspended.sharkId(), null))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("OWNER_NOT_ELIGIBLE");

        Principal closed = harness.closedIndividual();
        assertThatThrownBy(() -> harness.createAgent.execute(closed.sharkId(), null))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("OWNER_NOT_ELIGIBLE");
                    assertThat(ex).hasMessageContaining("CLOSED");
                });
    }

    @Test
    void rejectsAgentOwners() {
        Principal owner = harness.individual();
        Principal agent = created(owner.sharkId());
        assertThatThrownBy(() -> harness.createAgent.execute(agent.sharkId(), null))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("OWNER_NOT_ELIGIBLE");
    }

    @Test
    void supportsIdempotentReplay() {
        Principal owner = harness.individual();
        CreatePrincipalUseCase.Result first = harness.createAgent.execute(owner.sharkId(), "agent-key");
        CreatePrincipalUseCase.Result replay = harness.createAgent.execute(owner.sharkId(), "agent-key");

        assertThat(first).isInstanceOf(CreatePrincipalUseCase.Created.class);
        assertThat(replay).isInstanceOf(CreatePrincipalUseCase.Replayed.class);
        assertThat(((CreatePrincipalUseCase.Replayed) replay).principal())
                .isEqualTo(((CreatePrincipalUseCase.Created) first).principal());
    }

    private Principal created(SharkId ownerSharkId) {
        CreatePrincipalUseCase.Result result = harness.createAgent.execute(ownerSharkId, null);
        assertThat(result).isInstanceOf(CreatePrincipalUseCase.Created.class);
        return ((CreatePrincipalUseCase.Created) result).principal();
    }
}
