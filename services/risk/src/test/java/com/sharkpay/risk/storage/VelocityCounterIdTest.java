package com.sharkpay.risk.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityCounterIdTest {

    @Test
    void exposesItsCompositeKeyParts() {
        VelocityCounterId id = new VelocityCounterId("subject-1", "m12345", "KES");

        assertThat(id.getSubject()).isEqualTo("subject-1");
        assertThat(id.getWindowBucket()).isEqualTo("m12345");
        assertThat(id.getCurrency()).isEqualTo("KES");
    }

    @Test
    void noArgConstructorLeavesFieldsNullForJpa() {
        VelocityCounterId id = new VelocityCounterId();

        assertThat(id.getSubject()).isNull();
        assertThat(id.getWindowBucket()).isNull();
        assertThat(id.getCurrency()).isNull();
    }

    @Test
    void valueEqualityAndHash() {
        VelocityCounterId first = new VelocityCounterId("s", "m1", "KES");
        VelocityCounterId second = new VelocityCounterId("s", "m1", "KES");
        VelocityCounterId differentBucket = new VelocityCounterId("s", "m2", "KES");
        VelocityCounterId differentCurrency = new VelocityCounterId("s", "m1", "USD");
        VelocityCounterId differentSubject = new VelocityCounterId("t", "m1", "KES");

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(differentBucket);
        assertThat(first).isNotEqualTo(differentCurrency);
        assertThat(first).isNotEqualTo(differentSubject);
        assertThat(first).isNotEqualTo(null);
        assertThat(first).isNotEqualTo("s/m1/KES");
    }

    @Test
    void toStringJoinsTheKeyParts() {
        assertThat(new VelocityCounterId("subject-1", "m99", "KES").toString())
                .isEqualTo("subject-1/m99/KES");
    }
}
