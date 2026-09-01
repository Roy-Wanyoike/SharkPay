package com.sharkpay.identity.ports;

import java.time.OffsetDateTime;

/**
 * Time source port (UTC). Injected so tests can freeze/advance time.
 */
@FunctionalInterface
public interface Clock {

    OffsetDateTime now();
}
