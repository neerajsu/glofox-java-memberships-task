package com.glofox.memberships.idempotency.repository.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IdempotencyRecordTests {

    @Test
    void completesInProgressRecordAndAllowsIdenticalCompletionToReplay() {
        IdempotencyRecord record = record();
        UUID purchaseId = UUID.randomUUID();

        record.complete(purchaseId);
        record.complete(purchaseId);

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getPurchaseId()).isEqualTo(purchaseId);
    }

    @Test
    void rejectsCompletionWithDifferentPurchaseAfterTerminalState() {
        IdempotencyRecord record = record();
        record.complete(UUID.randomUUID());

        assertThatThrownBy(() -> record.complete(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsInProgressRecordAndAllowsIdenticalFailureToReplay() {
        IdempotencyRecord record = record();

        record.fail(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "Payment was declined");
        record.fail(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "Payment was declined");

        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(record.getFailureStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
    }

    private IdempotencyRecord record() {
        return new IdempotencyRecord("request-key", "request-hash", UUID.randomUUID(), "provider-key");
    }
}
