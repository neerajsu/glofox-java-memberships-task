package com.glofox.memberships.membership.repository.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PurchaseSagaTests {

    @Test
    void transitionsThroughPaymentAndFinalizationStates() {
        PurchaseSaga saga = saga();

        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        saga.markPaymentSucceeded();
        saga.markCompleted();

        assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.COMPLETED);
        assertThat(saga.getNextRecoveryAt()).isNull();
    }

    @Test
    void refusesPaymentSuccessBeforePaymentIsPending() {
        PurchaseSaga saga = saga();

        assertThatThrownBy(saga::markPaymentSucceeded).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPaymentCanBeDeferredAndReconciled() {
        PurchaseSaga saga = saga();
        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        saga.markPaymentUnknown(Instant.now().plusSeconds(10), "response lost");

        assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.PAYMENT_UNKNOWN);
        assertThat(saga.getNextRecoveryAt()).isAfter(Instant.now());
        saga.markPaymentSucceeded();
        assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.PAYMENT_SUCCEEDED);
    }

    @Test
    void compensationCanCompleteAfterPaymentDecline() {
        PurchaseSaga saga = saga();
        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        saga.beginCompensation(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "declined");
        saga.markCompensated();

        assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.COMPENSATED);
        assertThat(saga.getFailureStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED.value());
    }

    private PurchaseSaga saga() {
        PlanPurchaseDetails plan = new PlanPurchaseDetails(
                UUID.randomUUID(), "Pack", PlanType.PACK, 5_000, 10, true);
        PurchaseRequest request = new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2));
        return new PurchaseSaga(UUID.randomUUID(), "client-key", "provider-key", "Alice", "alice",
                plan, request, true, "correlation-id");
    }
}
