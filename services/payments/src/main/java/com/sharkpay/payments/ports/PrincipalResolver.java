package com.sharkpay.payments.ports;

import java.util.UUID;

/**
 * Resolves the calling principal (from the Keycloak JWT subject in
 * production; a fixed test principal in standalone MockMvc tests — security
 * is bypassed there, per the wallet/identity exemplars).
 */
public interface PrincipalResolver {

    UUID resolve();
}
