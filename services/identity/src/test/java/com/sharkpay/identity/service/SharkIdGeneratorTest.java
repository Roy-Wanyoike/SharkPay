package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.fakes.ScriptedRandomness;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharkIdGeneratorTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Test
    void generatesValidUniqueIds() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            SharkId id = harness.sharkIdGenerator.generate();
            assertThat(id.value()).matches("SP-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
            assertThat(seen.add(id.value())).isTrue();
        }
        assertThat(harness.principals.count()).isZero();
    }

    @Test
    void retriesOnCollision() {
        // first candidate is fully scripted (data "555555"); the collision
        // retry then draws fresh counter-based values and must succeed
        harness.randomness.repeat(5, SharkId.DATA_CHARS);
        String expectedFirst = "SP-" + "5555" + "-" + "55" + SharkId.checksumFor("555555");
        harness.principals.save(principalWithSharkId(expectedFirst));

        SharkId generated = harness.sharkIdGenerator.generate();

        assertThat(generated.value()).isNotEqualTo(expectedFirst);
        assertThat(generated.value()).matches("SP-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
        // only the seeded collision principal is stored; the generator itself persists nothing
        assertThat(harness.principals.count()).isEqualTo(1);
        assertThat(harness.principals.findBySharkId(generated)).isEmpty();
    }

    @Test
    void givesUpAfterMaxAttemptsAndReportsExhaustion() {
        harness.randomness.lockTo(5);
        String expected = "SP-" + "5555" + "-" + "55" + SharkId.checksumFor("555555");
        harness.principals.save(principalWithSharkId(expected));

        assertThatThrownBy(() -> harness.sharkIdGenerator.generate())
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unique SharkId")
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("SHARK_ID_GENERATION_EXHAUSTED");
    }

    @Test
    void scriptedRandomnessBehavesAsIntended() {
        ScriptedRandomness scripted = new ScriptedRandomness();
        assertThat(scripted.then(7).nextInt(32)).isEqualTo(7);
        assertThat(scripted.nextInt(32)).isNotEqualTo(7); // counter fallback
        ScriptedRandomness locked = new ScriptedRandomness().lockTo(9);
        assertThat(locked.nextInt(32)).isEqualTo(9);
        assertThat(locked.nextInt(32)).isEqualTo(9);
    }

    private Principal principalWithSharkId(String sharkId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-01T09:00:00Z");
        return new Principal(UUID.randomUUID(), SharkId.of(sharkId), PrincipalType.INDIVIDUAL,
                null, PrincipalStatus.ACTIVE,
                com.sharkpay.identity.domain.KycTier.UNVERIFIED, now, now);
    }
}
