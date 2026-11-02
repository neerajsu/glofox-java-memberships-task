package com.glofox.memberships.membership.service.purchase;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.saga.orchestration.PurchaseSagaOrchestrator;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryTransaction;
import com.glofox.memberships.membership.service.query.PurchaseQueryService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PurchaseServiceTests {

    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final PurchaseQueryService queries = mock(PurchaseQueryService.class);
    private final PurchaseSagaOrchestrator orchestrator = mock(PurchaseSagaOrchestrator.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final PurchaseInitializationService initialization = mock(PurchaseInitializationService.class);
    private final PurchaseSagaRecoveryTransaction terminalFailures = mock(PurchaseSagaRecoveryTransaction.class);
    private final PurchaseService service = new PurchaseService(idempotency, queries, orchestrator, sagas,
            initialization, terminalFailures);

    @Test
    void leavesIdempotencyInProgressWhenFailureLeavesSagaRecoverable() {
        var operation = new IdempotencyService.StartResult("key", UUID.randomUUID(), "provider-key", null, null);
        var request = request();
        var plan = new PlanPurchaseDetails(request.planId(), "Pack", com.glofox.memberships.plan.model.PlanType.PACK,
                5_000, 10, false);
        when(initialization.initialize("key", request))
                .thenReturn(new PurchaseInitializationService.InitializedPurchase(operation, plan));
        when(sagas.hasTerminalFailure(operation.operationId())).thenReturn(false);
        org.mockito.Mockito.when(orchestrator.execute(request, operation, plan))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_RETRY_EXHAUSTED", "retry exhausted"));

        assertThatThrownBy(() -> service.purchase(request, "key")).isInstanceOf(ApiException.class);

        verify(idempotency, never()).fail(eq("key"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void marksIdempotencyFailedWhenSagaHasReachedTerminalFailure() {
        var operation = new IdempotencyService.StartResult("key", UUID.randomUUID(), "provider-key", null, null);
        var request = request();
        var plan = new PlanPurchaseDetails(request.planId(), "Pack", com.glofox.memberships.plan.model.PlanType.PACK,
                5_000, 10, false);
        var failure = new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "declined");
        when(initialization.initialize("key", request))
                .thenReturn(new PurchaseInitializationService.InitializedPurchase(operation, plan));
        when(sagas.hasTerminalFailure(operation.operationId())).thenReturn(true);
        org.mockito.Mockito.when(orchestrator.execute(request, operation, plan)).thenThrow(failure);

        assertThatThrownBy(() -> service.purchase(request, "key")).isSameAs(failure);

        verify(idempotency).fail("key", failure);
    }

    @Test
    void atomicallyFailsSagaAndIdempotencyWhenFailureHappensBeforeReservations() {
        var operation = new IdempotencyService.StartResult("key", UUID.randomUUID(), "provider-key", null, null);
        var request = request();
        var plan = new PlanPurchaseDetails(request.planId(), "Pack", com.glofox.memberships.plan.model.PlanType.PACK,
                5_000, 10, false);
        var failure = new ApiException(HttpStatus.CONFLICT, "INVENTORY_EXHAUSTED", "no inventory");
        when(initialization.initialize("key", request))
                .thenReturn(new PurchaseInitializationService.InitializedPurchase(operation, plan));
        when(sagas.isBeforeResourceReservation(operation.operationId())).thenReturn(true);
        org.mockito.Mockito.when(orchestrator.execute(request, operation, plan)).thenThrow(failure);

        assertThatThrownBy(() -> service.purchase(request, "key")).isSameAs(failure);

        verify(terminalFailures).failBeforeResources(operation.operationId(), failure);
        verify(idempotency, never()).fail(eq("key"), org.mockito.ArgumentMatchers.any());
    }

    private PurchaseRequest request() {
        return new PurchaseRequest("Alice", UUID.randomUUID(), LocalDate.of(2026, 11, 2));
    }
}
