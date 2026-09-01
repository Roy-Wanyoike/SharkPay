package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.fakes.IdentityHarness;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void sha256HexMatchesKnownVector() {
        // sha256("abc") — well-known test vector
        assertThat(RequestFingerprint.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(RequestFingerprint.sha256Hex("")).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256HexIsCaseNormalizedHexOfFixedLength() {
        String fingerprint = RequestFingerprint.sha256Hex("create-principal|INDIVIDUAL|");
        assertThat(fingerprint).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void identicalCommandsShareAFingerprint() {
        String first = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.AGENT, "ABC123"));
        String second = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.AGENT, "ABC123"));
        assertThat(first).isEqualTo(second);
    }

    @Test
    void typeOrOwnerDifferencesChangeTheFingerprint() {
        String agent = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.AGENT, "ABC123"));
        String business = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.BUSINESS, null));
        String otherOwner = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.AGENT, "ABC124"));
        String noOwner = RequestFingerprint.ofCreatePrincipal(command(PrincipalType.AGENT, null));

        assertThat(agent).isNotEqualTo(business).isNotEqualTo(otherOwner).isNotEqualTo(noOwner);
        assertThat(business).isNotEqualTo(noOwner);
    }

    @Test
    void nullAndAbsentOwnersShareTheCanonicalForm() {
        String nullOwner = RequestFingerprint.ofCreatePrincipal(
                new CreatePrincipalUseCase.Command(PrincipalType.INDIVIDUAL, null, "k"));
        String individual = RequestFingerprint.ofCreatePrincipal(
                new CreatePrincipalUseCase.Command(PrincipalType.INDIVIDUAL, null, "other-key"));
        assertThat(nullOwner).isEqualTo(individual);
    }

    private static CreatePrincipalUseCase.Command command(PrincipalType type, String data) {
        SharkId owner = data == null ? null : IdentityHarness.validSharkId(data);
        return new CreatePrincipalUseCase.Command(type, owner, "ignored-key");
    }
}
