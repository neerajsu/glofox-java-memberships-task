package com.glofox.memberships.payment.repository.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentAttemptTests {

    @Test
    void retriesUnknownAttemptAndClearsPriorFailure() {
        PaymentAttempt attempt = attempt();
        attempt.markUnknown("response timed out");

        attempt.startAttempt();

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
        assertThat(attempt.getLastFailureType()).isNull();
        assertThat(attempt.getLastFailureMessage()).isNull();
    }

    @Test
    void recordsSuccessfulTransactionAndRejectsRetryAfterSuccess() {
        PaymentAttempt attempt = attempt();
        attempt.startAttempt();
        attempt.markSucceeded("provider-txn-1");

        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(attempt.getProviderTransactionId()).isEqualTo("provider-txn-1");
        assertThatThrownBy(attempt::startAttempt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordsDeclineAsTerminalAndRetryableFailureAsProcessing() {
        PaymentAttempt declined = attempt();
        declined.startAttempt();
        declined.markDeclined("card declined");
        assertThat(declined.getStatus()).isEqualTo(PaymentAttemptStatus.DECLINED);

        PaymentAttempt retryable = attempt();
        retryable.startAttempt();
        retryable.recordRetryableFailure(FailureType.TRANSIENT, "provider unavailable");
        assertThat(retryable.getStatus()).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(retryable.getLastFailureType()).isEqualTo(FailureType.TRANSIENT);
    }

    private PaymentAttempt attempt() {
        return new PaymentAttempt(UUID.randomUUID(), UUID.randomUUID(), "provider-key", "Alice", 5000, "cid");
    }
}
