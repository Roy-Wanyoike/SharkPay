package com.sharkpay.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.event.EventIds;
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
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wires the production configuration directly against the test fakes: every
 * bean factory method must build a usable object without a Spring context.
 */
class IdentityConfigTest {

    private final IdentityHarness harness = new IdentityHarness();
    private final IdentityConfig config = new IdentityConfig();

    @Test
    void clockBeanReturnsUtcNow() {
        Clock clock = config.clock();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
        OffsetDateTime after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5);
        assertThat(clock.now()).isBetween(before, after);
    }

    @Test
    void randomnessBeanProducesValuesWithinBound() {
        SecureRandomness randomness = (SecureRandomness) config.randomness();
        for (int i = 0; i < 1_000; i++) {
            int value = randomness.nextInt(32);
            assertThat(value).isBetween(0, 31);
        }
    }

    @Test
    void loggingEventPublisherEmitsWithoutThrowing() {
        LoggingEventPublisher publisher = (LoggingEventPublisher) config.eventPublisher();
        CloudEvent event = CloudEvent.of("identity.principal.created.v1", "sharkpay/identity",
                EventIds.uuidV7().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                Map.of("principal_id", "x"));
        publisher.publish(event);
        publisher.publish(CloudEvent.of("identity.kyc.tier.changed.v1", "sharkpay/identity",
                EventIds.uuidV7().toString(), OffsetDateTime.now(ZoneOffset.UTC), Map.of()));
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjects() {
        SharkIdGenerator sharkIdGenerator = config.sharkIdGenerator(harness.principalRepo(), harness.randomnessPort());
        CreatePrincipalUseCase createPrincipal = config.createPrincipalUseCase(
                harness.principalRepo(), sharkIdGenerator, harness.eventPublisher(),
                harness.idempotencyStore(), harness.clockPort());
        CreateAgentUseCase createAgent = config.createAgentUseCase(createPrincipal);
        GetPrincipalUseCase getPrincipal = config.getPrincipalUseCase(harness.principalRepo());
        ChangePrincipalStatusUseCase changeStatus = config.changePrincipalStatusUseCase(
                harness.principalRepo(), harness.eventPublisher(), harness.clockPort());
        VerifyKycUseCase verifyKyc = config.verifyKycUseCase(
                harness.principalRepo(), harness.kycRecords, harness.eventPublisher(), harness.clockPort());
        RegisterDeviceUseCase registerDevice = config.registerDeviceUseCase(
                harness.principalRepo(), harness.devices, harness.clockPort());
        ListDevicesUseCase listDevices = config.listDevicesUseCase(harness.principalRepo(), harness.devices);
        RevokeDeviceUseCase revokeDevice = config.revokeDeviceUseCase(harness.principalRepo(), harness.devices);

        // smoke: the whole wiring actually works end to end
        CreatePrincipalUseCase.Result created = createPrincipal.execute(
                new CreatePrincipalUseCase.Command(
                        com.sharkpay.identity.domain.PrincipalType.INDIVIDUAL, null, null));
        assertThat(created).isInstanceOf(CreatePrincipalUseCase.Created.class);
        assertThat(createAgent).isNotNull();
        assertThat(getPrincipal.byId(((CreatePrincipalUseCase.Created) created).principal().id())).isPresent();
        assertThat(verifyKyc).isNotNull();
        assertThat(registerDevice).isNotNull();
        assertThat(listDevices).isNotNull();
        assertThat(revokeDevice).isNotNull();
        assertThat(changeStatus).isNotNull();
    }
}
