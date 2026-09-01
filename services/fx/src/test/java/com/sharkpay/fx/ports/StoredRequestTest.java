package com.sharkpay.fx.ports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The idempotency record: fingerprint + conversion id, both required and
 * non-blank (they key the 409 idempotency_conflict detection).
 */
class StoredRequestTest {

    @Test
    void carriesTheFingerprintAndConversionId() {
        StoredRequest request = new StoredRequest("quote|src|dst", "cnv_" + "1".repeat(26));
        assertThat(request.requestFingerprint()).isEqualTo("quote|src|dst");
        assertThat(request.conversionId()).isEqualTo("cnv_" + "1".repeat(26));
        assertThat(request).isEqualTo(new StoredRequest("quote|src|dst", "cnv_" + "1".repeat(26)));
    }

    @Test
    void rejectsBlankFingerprintsAndConversionIds() {
        assertThatThrownBy(() -> new StoredRequest(null, "cnv_x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredRequest(" ", "cnv_x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredRequest("fingerprint", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StoredRequest("fingerprint", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
