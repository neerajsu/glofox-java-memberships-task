package com.glofox.memberships.membership.saga.orchestration.payment;

import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_ERROR_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.orchestration.compensation.PurchaseCompensationService;
import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaContext;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.plan.model.PlanType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PurchasePaymentFailureHandlerTests {
    private final PurchaseCompensationService compensation = mock(PurchaseCompensationService.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final PurchasePaymentOutcomeStore outcomes = mock(PurchasePaymentOutcomeStore.class);
    private final PurchasePaymentFailureHandler handler = new PurchasePaymentFailureHandler(compensation, sagas, outcomes);
    private final UUID id = UUID.randomUUID();
    private final PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5000, 10, false);
    private final PurchaseSagaContext context = new PurchaseSagaContext(
            new StartResult("client-key", id, "provider-key", null, null),
            new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2)), plan, "alice", "cid");
    private final PaymentCommand command = new PaymentCommand(UUID.randomUUID(), "provider-key", 5000, "Alice", id);

    @Test
    void declinePersistenceFailurePropagatesBeforeSagaLookupOrCompensation() {
        var failure = new IllegalStateException("decline persistence failed");
        doThrow(failure).when(outcomes).declined(id, "The payment was declined");

        assertThatThrownBy(() -> handler.handleDecline(context, command)).isSameAs(failure);

        verifyNoInteractions(sagas, compensation);
    }

    @Test
    void terminalFailurePersistenceFailurePropagatesBeforeSagaLookupOrCompensation() {
        var failure = new IllegalStateException("provider outcome persistence failed");
        doThrow(failure).when(outcomes).failed(eq(id), eq(FailureType.PERMANENT),
                eq(HttpStatus.BAD_GATEWAY), eq(PAYMENT_PROVIDER_ERROR_CODE), anyString());

        assertThatThrownBy(() -> handler.handleProviderFailure(context, command,
                new PaymentProviderException(FailureType.PERMANENT, "provider failed"))).isSameAs(failure);

        verifyNoInteractions(sagas, compensation);
    }

    @Test
    void unknownOutcomePersistenceFailurePropagatesWithoutCompensation() {
        var failure = new IllegalStateException("unknown outcome persistence failed");
        doThrow(failure).when(outcomes).unknown(eq(id), any(), anyString());

        assertThatThrownBy(() -> handler.handleProviderFailure(context, command,
                new PaymentProviderException(FailureType.UNKNOWN, "provider timed out"))).isSameAs(failure);

        verifyNoInteractions(sagas, compensation);
    }

    @Test
    void compensationFailureIsNotHiddenBySanitizedDeclineResponse() {
        PurchaseSaga saga = mock(PurchaseSaga.class);
        when(sagas.findById(id)).thenReturn(saga);
        var failure = new IllegalStateException("compensation failed");
        doThrow(failure).when(compensation).compensate(saga);

        assertThatThrownBy(() -> handler.handleDecline(context, command)).isSameAs(failure);

        verify(outcomes).declined(id, "The payment was declined");
        verify(compensation).compensate(saga);
    }

    @Test
    void sagaLookupFailurePropagatesAfterTerminalOutcomeHasBeenRecorded() {
        var failure = new IllegalStateException("saga lookup failed");
        when(sagas.findById(id)).thenThrow(failure);

        assertThatThrownBy(() -> handler.handleProviderFailure(context, command,
                new PaymentProviderException(FailureType.PERMANENT, "provider failed"))).isSameAs(failure);

        verify(outcomes).failed(eq(id), eq(FailureType.PERMANENT), eq(HttpStatus.BAD_GATEWAY),
                eq(PAYMENT_PROVIDER_ERROR_CODE), anyString());
        verifyNoInteractions(compensation);
    }

    @Test
    void unknownOutcomeReturns503WithoutLoadingSagaOrReleasingResources() {
        var failure = handler.handleProviderFailure(context, command,
                new PaymentProviderException(FailureType.UNKNOWN, "provider timed out"));

        assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failure.getCode()).isEqualTo(PAYMENT_OUTCOME_UNKNOWN_CODE);
        verify(outcomes).unknown(eq(id), any(), eq("provider timed out"));
        verifyNoInteractions(sagas, compensation);
    }
}
