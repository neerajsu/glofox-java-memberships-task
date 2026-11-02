package com.glofox.memberships.membership.saga.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import com.glofox.memberships.plan.model.PlanType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PurchasePaymentOutcomeStoreTests {

    private final PurchaseSagaRepository sagas = mock(PurchaseSagaRepository.class);
    private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final PurchasePaymentOutcomeStore store = new PurchasePaymentOutcomeStore(sagas, attempts, retry);

    @Test
    void recordsConfirmedPaymentOnAttemptAndSaga() {
        UUID operationId = UUID.randomUUID();
        PaymentAttempt payment = payment(operationId);
        PurchaseSaga saga = saga(operationId);
        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        when(attempts.findByPurchaseAttemptId(operationId)).thenReturn(Optional.of(payment));
        when(sagas.findById(operationId)).thenReturn(Optional.of(saga));
        runRetryInline();

        store.succeeded(operationId, "txn-confirmed");

        assertThat(payment.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(payment.getProviderTransactionId()).isEqualTo("txn-confirmed");
        assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.PAYMENT_SUCCEEDED);
    }

    @Test
    void refusesToReplaceConfirmedPaymentWithUnknownOutcome() {
        UUID operationId = UUID.randomUUID();
        PaymentAttempt payment = payment(operationId);
        payment.startAttempt();
        payment.markSucceeded("txn-confirmed");
        when(attempts.findByPurchaseAttemptId(operationId)).thenReturn(Optional.of(payment));
        when(sagas.findById(operationId)).thenReturn(Optional.of(saga(operationId)));
        runRetryInline();

        store.unknown(operationId, Instant.now(), "late response");

        assertThat(payment.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    void refusesToMarkSuccessfulPaymentAsDeclined() {
        UUID operationId = UUID.randomUUID();
        PaymentAttempt payment = payment(operationId);
        payment.startAttempt();
        payment.markSucceeded("txn-confirmed");
        when(attempts.findByPurchaseAttemptId(operationId)).thenReturn(Optional.of(payment));
        when(sagas.findById(operationId)).thenReturn(Optional.of(saga(operationId)));
        runRetryInline();

        assertThatThrownBy(() -> store.declined(operationId, "declined"))
                .isInstanceOf(IllegalStateException.class);
    }

    private void runRetryInline() {
        when(retry.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<Object> action = invocation.getArgument(0);
            return action.get();
        });
    }

    private PaymentAttempt payment(UUID operationId) {
        return new PaymentAttempt(UUID.randomUUID(), operationId, "provider-key", "Alice", 5_000, "cid");
    }

    private PurchaseSaga saga(UUID operationId) {
        var plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5_000, 10, false);
        return new PurchaseSaga(operationId, "client-key", "provider-key", "Alice", "alice", plan,
                new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2)), false, "cid");
    }
}
