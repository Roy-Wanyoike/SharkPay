package com.sharkpay.identity.config;

import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.event.EventPublisher;
import com.sharkpay.identity.ports.IdempotencyStore;
import com.sharkpay.identity.ports.KycRepository;
import com.sharkpay.identity.ports.PrincipalRepository;
import com.sharkpay.identity.ports.DeviceRepository;
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
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the hexagon: use-cases depend only on ports; production port
 * adapters are the JPA repositories (storage package), SecureRandomness and
 * the logging event publisher until the NATS/Kafka adapter lands.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfig {

    @Bean
    public Clock clock() {
        return () -> OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Bean
    public Randomness randomness() {
        return new SecureRandomness();
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public SharkIdGenerator sharkIdGenerator(PrincipalRepository principalRepository, Randomness randomness) {
        return new SharkIdGenerator(principalRepository, randomness);
    }

    @Bean
    public CreatePrincipalUseCase createPrincipalUseCase(PrincipalRepository principalRepository,
                                                         SharkIdGenerator sharkIdGenerator,
                                                         EventPublisher eventPublisher,
                                                         IdempotencyStore idempotencyStore,
                                                         Clock clock) {
        return new CreatePrincipalUseCase(principalRepository, sharkIdGenerator, eventPublisher,
                idempotencyStore, clock);
    }

    @Bean
    public CreateAgentUseCase createAgentUseCase(CreatePrincipalUseCase createPrincipalUseCase) {
        return new CreateAgentUseCase(createPrincipalUseCase);
    }

    @Bean
    public GetPrincipalUseCase getPrincipalUseCase(PrincipalRepository principalRepository) {
        return new GetPrincipalUseCase(principalRepository);
    }

    @Bean
    public ChangePrincipalStatusUseCase changePrincipalStatusUseCase(PrincipalRepository principalRepository,
                                                                     EventPublisher eventPublisher,
                                                                     Clock clock) {
        return new ChangePrincipalStatusUseCase(principalRepository, eventPublisher, clock);
    }

    @Bean
    public VerifyKycUseCase verifyKycUseCase(PrincipalRepository principalRepository,
                                             KycRepository kycRepository,
                                             EventPublisher eventPublisher,
                                             Clock clock) {
        return new VerifyKycUseCase(principalRepository, kycRepository, eventPublisher, clock);
    }

    @Bean
    public RegisterDeviceUseCase registerDeviceUseCase(PrincipalRepository principalRepository,
                                                       DeviceRepository deviceRepository,
                                                       Clock clock) {
        return new RegisterDeviceUseCase(principalRepository, deviceRepository, clock);
    }

    @Bean
    public ListDevicesUseCase listDevicesUseCase(PrincipalRepository principalRepository,
                                                 DeviceRepository deviceRepository) {
        return new ListDevicesUseCase(principalRepository, deviceRepository);
    }

    @Bean
    public RevokeDeviceUseCase revokeDeviceUseCase(PrincipalRepository principalRepository,
                                                   DeviceRepository deviceRepository) {
        return new RevokeDeviceUseCase(principalRepository, deviceRepository);
    }
}
