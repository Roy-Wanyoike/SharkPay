package com.sharkpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharkIdTest {

    private static final String ALPHABET = SharkId.ALPHABET;

    @Test
    void generatedIdsMatchTheDocumentedFormat() {
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            String data = randomData(random);
            SharkId id = SharkId.fromData(data);
            assertThat(id.value()).matches("SP-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}");
            assertThat(id.value()).startsWith("SP-" + data.substring(0, 4) + "-" + data.substring(4));
            // re-parse must succeed (checksum self-consistent)
            assertThatCode(() -> SharkId.of(id.value())).doesNotThrowAnyException();
        }
    }

    @Test
    void everyAlphabetCharacterIsAcceptedAsData() {
        // each single alphabet char, repeated 6 times, yields a valid id
        for (char c : ALPHABET.toCharArray()) {
            String data = String.valueOf(c).repeat(6);
            assertThatCode(() -> SharkId.fromData(data)).doesNotThrowAnyException();
        }
    }

    @Test
    void checksumExampleIsDeterministic() {
        // D = 0 -> check = (98 - 0) % 97 = 1 -> check chars '0','1'
        assertThat(SharkId.checksumFor("000000")).isEqualTo("01");
        assertThat(SharkId.fromData("000000").value()).isEqualTo("SP-0000-0001");
    }

    @Test
    void checksumForAlwaysProducesAValidId() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            String data = randomData(random);
            String check = SharkId.checksumFor(data);
            assertThat(check).hasSize(2).matches("[0-9A-HJKMNP-TV-Z]{2}");
            assertThatCode(() -> SharkId.of("SP-" + data.substring(0, 4) + "-" + data.substring(4) + check))
                    .doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "SP-0000-0001 ", " SP-0000-0001", "sp-0000-0001",
            "SP_0000_0001", "SP-0000-000", "SP-0000-00011", "XX-0000-0001",
            "SP-AAAA", "SP-000000-0001"
    })
    void malformedIdsAreRejected(String raw) {
        assertThatThrownBy(() -> SharkId.of(raw))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).code())
                .isEqualTo("INVALID_SHARK_ID");
    }

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> SharkId.of(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void crockfordExcludedCharactersAreRejected() {
        // I, L, O and U are not in the Crockford base32 alphabet
        for (char bad : new char[]{'I', 'L', 'O', 'U', 'i', 'l', 'o', 'u'}) {
            String data = "00000" + bad;
            String id = "SP-" + data.substring(0, 4) + "-" + data.substring(4) + "01";
            assertThatThrownBy(() -> SharkId.of(id))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Crockford");
        }
    }

    @Test
    void wrongChecksumIsRejected() {
        // correct id for data 000000 is SP-0000-0001; corrupt the check pair
        assertThatThrownBy(() -> SharkId.of("SP-0000-0002"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("checksum");
        assertThatThrownBy(() -> SharkId.of("SP-0000-0000"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void checkPairValuesAtOrAbove97AreRejected() {
        // check pair decoding to >= 97 is out of the generated space
        // "31" decodes to 3*32+1 = 97
        assertThatThrownBy(() -> SharkId.of("SP-0000-0031"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void everySingleCharacterMutationIsDetected() {
        Random random = new Random(99);
        String value = SharkId.fromData(randomData(random)).value();
        int[] bodyPositions = {3, 4, 5, 6, 8, 9, 10, 11}; // the 8 base32 positions
        for (int position : bodyPositions) {
            for (char replacement : ALPHABET.toCharArray()) {
                if (replacement == value.charAt(position)) {
                    continue;
                }
                String mutated = value.substring(0, position) + replacement + value.substring(position + 1);
                assertThatThrownBy(() -> SharkId.of(mutated))
                        .as("mutating position %d of %s to %s must be rejected", position, value, replacement)
                        .isInstanceOf(ValidationException.class);
            }
        }
    }

    @Test
    void fromDataRejectsWrongLength() {
        assertThatThrownBy(() -> SharkId.fromData(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SharkId.fromData("12345")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SharkId.fromData("1234567")).isInstanceOf(ValidationException.class);
    }

    @Test
    void generatedIdsAreUniqueForDistinctData() {
        Random random = new Random(1234);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            assertThat(seen.add(SharkId.fromData(randomData(random)).value())).isTrue();
        }
    }

    @Test
    void valueObjectSemantics() {
        SharkId first = SharkId.of("SP-0000-0001");
        SharkId second = SharkId.of("SP-0000-0001");
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
        assertThat(first.toString()).isEqualTo("SP-0000-0001");
        assertThat(first.value()).isEqualTo("SP-0000-0001");
        assertThat(first).isNotEqualTo(SharkId.fromData("000001"));
        assertThat(first).isNotEqualTo("SP-0000-0001");
        assertThat(first).isNotEqualTo(null);
    }

    private static String randomData(Random random) {
        StringBuilder data = new StringBuilder(SharkId.DATA_CHARS);
        for (int i = 0; i < SharkId.DATA_CHARS; i++) {
            data.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return data.toString();
    }

    @Nested
    class ChecksumMath {

        @Test
        void validityConditionHoldsForKnownIds() {
            // (D * 1024 + P) mod 97 == 1
            assertThat(SharkId.of("SP-0000-0001")).isNotNull();
        }

        @Test
        void checksumForComputesTheMod97Inverse() {
            // data "7ZZZZZ": verify the checksum through the public contract
            String data = "7ZZZZZ";
            String id = "SP-" + data.substring(0, 4) + "-" + data.substring(4) + SharkId.checksumFor(data);
            assertThatCode(() -> SharkId.of(id)).doesNotThrowAnyException();
        }
    }
}
