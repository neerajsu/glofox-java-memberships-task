package com.glofox.memberships.membership.saga.orchestration.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseStatus;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import com.glofox.memberships.membership.saga.orchestration.PurchaseSagaOrchestrator;
import com.glofox.memberships.membership.saga.orchestration.payment.PurchasePaymentPreparation;
import com.glofox.memberships.membership.saga.orchestration.payment.PurchasePaymentProcessor;
import com.glofox.memberships.membership.saga.orchestration.reservation.PurchaseResourceReservation;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.membership.service.purchase.PurchaseFinalizer;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import com.glofox.memberships.plan.model.PlanType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PurchaseSagaFlowFactoryTests {
    private final PurchaseResourceReservation reservation = mock(PurchaseResourceReservation.class);
    private final PurchasePaymentPreparation preparation = mock(PurchasePaymentPreparation.class);
    private final PaymentAttemptStore attempts = mock(PaymentAttemptStore.class);
    private final PurchasePaymentProcessor payments = mock(PurchasePaymentProcessor.class);
    private final PurchaseFinalizer finalizer = mock(PurchaseFinalizer.class);
    private final PurchaseSagaFlowFactory factory = new PurchaseSagaFlowFactory(reservation, preparation, attempts, payments, finalizer);
    private final UUID id = UUID.randomUUID();
    private final StartResult operation = new StartResult("client-key", id, "provider-key", null, null);
    private final PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5000, 10, false);
    private final PurchaseRequest request = new PurchaseRequest(" Alice ", plan.id(), LocalDate.of(2026, 11, 2));

    @BeforeEach
    void setup() {
        when(attempts.findByPurchaseAttemptId(id)).thenReturn(new PaymentAttempt(UUID.randomUUID(), id,
                operation.providerKey(), request.memberName(), plan.priceCents(), "cid"));
        when(payments.charge(any(), any())).thenReturn("txn");
        when(finalizer.finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey()))
                .thenReturn(new MembershipPurchase(id, request.memberName(), "alice", plan.id(), plan.name(), plan.type(),
                        plan.priceCents(), plan.credits(), request.startDate(), PurchaseStatus.ACTIVE, "txn", Instant.parse("2026-11-02T12:00:00Z")));
    }

    @Test
    void orchestratorExecutesBusinessStepsInOrderWithStableContext() {
        var orchestrator = new PurchaseSagaOrchestrator(factory, finalizer);
        var response = orchestrator.execute(request, operation, plan);
        assertThat(response.id()).isEqualTo(id);
        var context = ArgumentCaptor.forClass(PurchaseSagaContext.class);
        var order = inOrder(reservation, preparation, attempts, payments, finalizer);
        order.verify(reservation).reserve(id, "alice", plan);
        order.verify(preparation).prepare(id, operation.providerKey(), request.memberName(), 5000, id.toString());
        order.verify(attempts).findByPurchaseAttemptId(id);
        order.verify(payments).charge(context.capture(), any());
        order.verify(finalizer).finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey());
        assertThat(context.getValue().memberKey()).isEqualTo("alice");
        assertThat(context.getValue().operation()).isSameAs(operation);
        assertThat(context.getValue().correlationId()).isEqualTo(id.toString());
    }

    @Test
    void factoryAlwaysCreatesFreshFlow() {
        var first = factory.start(operation, request, plan);
        var second = factory.start(operation, request, plan);
        assertThat(first).isNotSameAs(second);
        first.reserveResources();
        assertThatThrownBy(second::preparePayment).isInstanceOf(IllegalStateException.class);
        second.reserveResources();
        verify(reservation, times(2)).reserve(id, "alice", plan);
    }

    @Test
    void outOfOrderStepsFailBeforeAnyPersistenceOrPayment() {
        assertThatThrownBy(factory.start(operation, request, plan)::finalizePurchase).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory.start(operation, request, plan)::preparePayment).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(factory.start(operation, request, plan)::chargePayment).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservation, preparation, attempts, payments, finalizer);
    }

    @Test
    void resourceFailureStopsFlowAndCannotRepeatStepOnSameFlow() {
        var failure = new IllegalStateException("reservation failed");
        doThrow(failure).when(reservation).reserve(id, "alice", plan);
        var flow = factory.start(operation, request, plan);
        assertThatThrownBy(flow::reserveResources).isSameAs(failure);
        assertThatThrownBy(flow::reserveResources).isInstanceOf(IllegalStateException.class);
        verify(reservation, times(1)).reserve(id, "alice", plan);
        verifyNoInteractions(preparation, attempts, payments, finalizer);
    }

    @Test
    void unknownOutcomeStopsBeforeFinalizationAndCannotRechargeUsingSameFlow() {
        var failure = new PaymentProviderException(FailureType.UNKNOWN, "pending reconciliation");
        when(payments.charge(any(), any())).thenThrow(failure);
        var flow = factory.start(operation, request, plan).reserveResources().preparePayment();
        assertThatThrownBy(flow::chargePayment).isSameAs(failure);
        assertThatThrownBy(flow::chargePayment).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(flow::finalizePurchase).isInstanceOf(IllegalStateException.class);
        verify(payments, times(1)).charge(any(), any());
        verifyNoInteractions(finalizer);
    }

    @Test
    void finalizationFailureDoesNotReenterPaymentStep() {
        var failure = new PaymentProviderException(FailureType.PERMANENT, "failure after confirmed payment");
        doThrow(failure).when(finalizer).finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey());
        var flow = factory.start(operation, request, plan).reserveResources().preparePayment().chargePayment();
        assertThatThrownBy(flow::finalizePurchase).isSameAs(failure);
        assertThatThrownBy(flow::chargePayment).isInstanceOf(IllegalStateException.class);
        verify(payments, times(1)).charge(any(), any());
        verify(finalizer, times(1)).finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey());
    }

    @Test
    void completedFlowCannotFinalizeTwice() {
        var flow = factory.start(operation, request, plan).reserveResources().preparePayment().chargePayment();
        flow.finalizePurchase();
        assertThatThrownBy(flow::finalizePurchase).isInstanceOf(IllegalStateException.class);
        verify(finalizer, times(1)).finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey());
    }

    @Test
    void recoveryFinalizationEntryPointSkipsEntireFluentChargeFlow() {
        var response = new PurchaseSagaOrchestrator(factory, finalizer)
                .finalizePurchase(id, request, "alice", plan, "txn", operation.clientKey());
        assertThat(response.id()).isEqualTo(id);
        verifyNoInteractions(reservation, preparation, attempts, payments);
    }
}
