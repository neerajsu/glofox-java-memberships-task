package com.glofox.memberships.membership.saga.orchestration.compensation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PurchaseCompensationTransactionTests {

    private final InventoryReservationService inventory = mock(InventoryReservationService.class);
    private final UnlimitedMembershipClaimService claims = mock(UnlimitedMembershipClaimService.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final PurchaseCompensationTransaction transaction =
            new PurchaseCompensationTransaction(inventory, claims, sagas, idempotency);

    @Test
    void releasesReservedInventoryClaimAndFailsIdempotencyDuringCompensation() {
        UUID operationId = UUID.randomUUID();
        PurchaseSaga saga = saga(operationId, PlanType.UNLIMITED, true);
        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        saga.beginCompensation(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "declined");
        when(sagas.findById(operationId)).thenReturn(saga);

        transaction.compensate(operationId);

        verify(inventory).release(operationId);
        verify(claims).releasePending("alice", operationId);
        verify(sagas).compensated(operationId);
        verify(idempotency).failIfInProgress(eq("client-key"), org.mockito.ArgumentMatchers.any());
    }

    private PurchaseSaga saga(UUID id, PlanType type, boolean finiteInventory) {
        PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Plan", type, 5_000,
                type == PlanType.PACK ? 10 : null, finiteInventory);
        return new PurchaseSaga(id, "client-key", "provider-key", "Alice", "alice", plan,
                new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2)), finiteInventory, "cid");
    }
}
