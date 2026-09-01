package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.IdempotentRequest;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CreatePrincipalUseCaseTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Nested
    class Creation {

        @Test
        void createsIndividualBusinessAndAgentPrincipals() {
            Principal individual = created(new CreatePrincipalUseCase.Command(PrincipalType.INDIVIDUAL, null, null));
            assertThat(individual.status()).isEqualTo(PrincipalStatus.ACTIVE);
            assertThat(individual.kycTier()).isEqualTo(com.sharkpay.identity.domain.KycTier.UNVERIFIED);
            assertThat(individual.ownerPrincipalId()).isNull();

            Principal business = created(new CreatePrincipalUseCase.Command(PrincipalType.BUSINESS, null, null));
            assertThat(business.type()).isEqualTo(PrincipalType.BUSINESS);

            Principal owner = individual;
            Principal agent = created(new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), null));
            assertThat(agent.type()).isEqualTo(PrincipalType.AGENT);
            assertThat(agent.ownerPrincipalId()).isEqualTo(owner.id());

            assertThat(harness.principals.count()).isEqualTo(3);
            assertThat(harness.principals.findBySharkId(individual.sharkId())).contains(individual);
        }

        @Test
        void publishesPrincipalCreatedEventWithFullPayload() {
            Principal owner = harness.individual();
            Principal agent = created(new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), null));

            // the owner was seeded directly (no event); only the agent creation publishes
            assertThat(harness.events.byType(IdentityEvents.PRINCIPAL_CREATED)).hasSize(1);
            CloudEvent event = harness.events.last();
            assertThat(event.type()).isEqualTo("identity.principal.created.v1");
            assertThat(event.specversion()).isEqualTo("1.0");
            assertThat(event.source()).isEqualTo("sharkpay/identity");
            assertThat(event.time()).isEqualTo(harness.clock.now());
            assertThat(event.id()).isNotBlank();
            assertThat(event.data())
                    .containsEntry("principal_id", agent.id().toString())
                    .containsEntry("shark_id", agent.sharkId().value())
                    .containsEntry("type", "AGENT")
                    .containsEntry("status", "ACTIVE")
                    .containsEntry("owner_principal_id", owner.id().toString())
                    .containsEntry("kyc_tier", "UNVERIFIED");
        }

        @Test
        void ownerRulesAreEnforced() {
            // AGENT without owner
            assertThatThrownBy(() -> harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, null, null)))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("OWNER_REQUIRED");

            // non-AGENT with owner
            assertThatThrownBy(() -> harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.INDIVIDUAL,
                            IdentityHarness.validSharkId("AAAAAA"), null)))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("OWNER_NOT_ALLOWED");

            // owner does not exist
            assertThatThrownBy(() -> harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT,
                            IdentityHarness.validSharkId("ZZZZZZ"), null)))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("OWNER_NOT_FOUND");

            // owner is an agent itself
            Principal owner = harness.individual();
            Principal agent = created(new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), null));
            assertThatThrownBy(() -> harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, agent.sharkId(), null)))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("OWNER_NOT_ELIGIBLE");

            // owner is suspended
            Principal suspendedOwner = harness.individual();
            harness.principals.save(suspendedOwner.withStatus(PrincipalStatus.SUSPENDED, harness.clock.now()));
            assertThatThrownBy(() -> harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, suspendedOwner.sharkId(), null)))
                    .isInstanceOfSatisfying(ValidationException.class, ex -> {
                        assertThat(ex.code()).isEqualTo("OWNER_NOT_ELIGIBLE");
                        assertThat(ex).hasMessageContaining("SUSPENDED");
                    });
        }

        @Test
        void commandIsRequired() {
            assertThatThrownBy(() -> harness.createPrincipal.execute(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void sameKeyAndBodyReplaysTheOriginalPrincipalWithoutSideEffects() {
            Principal owner = harness.individual();
            CreatePrincipalUseCase.Command command =
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), "key-1");

            CreatePrincipalUseCase.Created first = (CreatePrincipalUseCase.Created) harness.createPrincipal.execute(command);
            harness.events.reset();

            CreatePrincipalUseCase.Replayed replay =
                    (CreatePrincipalUseCase.Replayed) harness.createPrincipal.execute(command);

            assertThat(replay.principal()).isEqualTo(first.principal());
            assertThat(harness.principals.count()).isEqualTo(2); // owner + agent only
            assertThat(harness.idempotency.count()).isEqualTo(1);
            assertThat(harness.events.events()).isEmpty(); // no re-publish on replay
        }

        @Test
        void sameKeyWithDifferentBodyConflicts() {
            Principal owner = harness.individual();
            harness.createPrincipal.execute(new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), "key-1"));

            CreatePrincipalUseCase.Result result = harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.BUSINESS, null, "key-1"));

            assertThat(result).isInstanceOf(CreatePrincipalUseCase.Conflict.class);
            assertThat(((CreatePrincipalUseCase.Conflict) result).idempotencyKey()).isEqualTo("key-1");
            assertThat(harness.principals.count()).isEqualTo(2); // nothing new created
        }

        @Test
        void differentOwnerConflictsUnderTheSameKey() {
            Principal firstOwner = harness.individual();
            Principal secondOwner = harness.individual();
            harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, firstOwner.sharkId(), "key-2"));

            CreatePrincipalUseCase.Result result = harness.createPrincipal.execute(
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, secondOwner.sharkId(), "key-2"));

            assertThat(result).isInstanceOf(CreatePrincipalUseCase.Conflict.class);
        }

        @Test
        void withoutKeyNothingIsStored() {
            created(new CreatePrincipalUseCase.Command(PrincipalType.INDIVIDUAL, null, null));
            assertThat(harness.idempotency.count()).isZero();
        }

        @Test
        void replayOfAMissingPrincipalIsAnExplicit404() {
            Principal owner = harness.individual();
            CreatePrincipalUseCase.Command command =
                    new CreatePrincipalUseCase.Command(PrincipalType.AGENT, owner.sharkId(), "key-1");
            CreatePrincipalUseCase.Created first = (CreatePrincipalUseCase.Created) harness.createPrincipal.execute(command);

            // simulate the referenced principal vanishing (e.g. manual purge)
            harness.principals.save(first.principal().withStatus(PrincipalStatus.CLOSED, harness.clock.now()));
            harness.principals.all().removeIf(p -> p.id().equals(first.principal().id()));

            assertThatThrownBy(() -> harness.createPrincipal.execute(command))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }

        @Test
        void idempotentRequestsAreImmutableValueRecords() {
            IdempotentRequest request = new IdempotentRequest("k", "f", UUID.randomUUID());
            assertThat(request.key()).isEqualTo("k");
            assertThat(request.requestFingerprint()).isEqualTo("f");
            assertThatThrownBy(() -> new IdempotentRequest(null, "f", UUID.randomUUID()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new IdempotentRequest("k", null, UUID.randomUUID()))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new IdempotentRequest("k", "f", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    private Principal created(CreatePrincipalUseCase.Command command) {
        CreatePrincipalUseCase.Result result = harness.createPrincipal.execute(command);
        assertThat(result).isInstanceOf(CreatePrincipalUseCase.Created.class);
        return ((CreatePrincipalUseCase.Created) result).principal();
    }
}
