package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Route + initiate activity: router hard filters + scoring decide the
 * provider, the gateway is pre-flighted, initiation is idempotent on the
 * internal id, business outcomes come back as data.
 */
class ProviderHandoffUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void routesToTheScoredProviderAndInitiates() {
        // two eligible candidates: the cheaper one must win
        env.gateway.addCandidate(new ProviderGatewayPort.ProviderCandidateView("premium",
                java.util.List.of("honeycoin"), java.util.List.of("KES"), java.util.List.of("KE"),
                200, 500, 9_900, false, 0, null, null));
        PaymentIntent intent = env.createDefault();

        assertThat(intent.provider()).isEqualTo("honeycoin"); // 40 bps beats 200 bps

        // the initiate call carried the payment's internal id as the
        // adapter-level idempotency key and the exact amount
        ProviderGatewayPort.InitiateRequest sent = env.gateway.initiations().get(0);
        assertThat(sent.transactionKey()).isEqualTo(intent.internalId().toString());
        assertThat(sent.amountMinor()).isEqualTo(150_000);
        assertThat(sent.currency()).isEqualTo("KES");
        assertThat(sent.rail()).isEqualTo("honeycoin");
        assertThat(sent.destination()).isEqualTo(PaymentsTestEnv.WALLET);
        assertThat(intent.providerRef()).isEqualTo(
                env.gateway.initiatedByKey().get(intent.internalId().toString()).ref());
    }

    @Test
    void tierRankGatesTieredCandidates() {
        env.gateway.clearCandidates();
        env.gateway.addCandidate(new ProviderGatewayPort.ProviderCandidateView("vip-only",
                java.util.List.of("honeycoin"), java.util.List.of("KES"), java.util.List.of("KE"),
                10, 500, 9_900, false, 2, null, null));

        // limited KYC (tier 1): the tier-2 candidate is ineligible → no route
        env.risk.byDefault(com.sharkpay.payments.fakes.FakeRiskPort.allow(1));
        PaymentIntent limited = env.create("k-limited");
        assertThat(limited.state()).isEqualTo(PaymentState.FAILED);
        assertThat(limited.failureReason()).contains("no_eligible_provider");
        assertThat(env.gateway.initiations()).isEmpty();

        // full KYC (tier 2): the same candidate serves the payment
        PaymentsTestEnv fullKyc = new PaymentsTestEnv();
        fullKyc.gateway.clearCandidates();
        fullKyc.gateway.addCandidate(new ProviderGatewayPort.ProviderCandidateView("vip-only",
                java.util.List.of("honeycoin"), java.util.List.of("KES"), java.util.List.of("KE"),
                10, 500, 9_900, false, 2, null, null));
        PaymentIntent full = fullKyc.create("k-full");
        assertThat(full.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(full.provider()).isEqualTo("vip-only");
    }

    @Test
    void handoffReplaysWithoutASecondWireCall() {
        PaymentIntent intent = env.createDefault();

        var replay = env.handoff.handoff(intent.id(), 2);

        assertThat(replay.type()).isEqualTo(ProviderHandoffUseCase.Result.Type.INITIATED);
        assertThat(replay.detail()).isEqualTo("replay");
        assertThat(env.gateway.initiations()).hasSize(1);
        assertThat(env.gateway.quotes()).hasSize(1); // pre-flight also skipped
    }

    @Test
    void skippedWhenNoLongerPendingProvider() {
        PaymentIntent intent = env.createDefault();
        env.cancelPayment.cancel("ck", intent.id());

        var result = env.handoff.handoff(intent.id(), 2);

        assertThat(result.type()).isEqualTo(ProviderHandoffUseCase.Result.Type.SKIPPED);
        assertThat(result.detail()).contains("CANCELLED");
    }

    @Test
    void noCandidatesAtAllIsNoRouteFailClosed() {
        env.gateway.clearCandidates();
        PaymentIntent intent = env.createDefault(); // create compensates → FAILED

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(intent.failureReason()).contains("no_eligible_provider");
        assertThat(env.gateway.initiations()).isEmpty();
        // the quote pre-flight never ran (nothing routed)
        assertThat(env.gateway.quotes()).isEmpty();
    }

    @Test
    void unservableQuoteIsNoRoute() {
        env.gateway.rejectQuotesFor("KES", 1);
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(intent.failureReason()).contains("no liquidity");
        // quote ran once; initiate never did
        assertThat(env.gateway.quotes()).hasSize(1);
        assertThat(env.gateway.initiations()).isEmpty();
    }

    @Test
    void railRejectionIsReportedAsDataNotAnException() {
        env.gateway.rejectNextInitiations(1);
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(env.gateway.initiations()).hasSize(1); // the rejected attempt
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
    }

    @Test
    void unknownPaymentsAreNotFound() {
        assertThatThrownBy(() -> env.handoff.handoff("pay_0123456789abcdef0123456789abcdee", 2))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
    }
}
