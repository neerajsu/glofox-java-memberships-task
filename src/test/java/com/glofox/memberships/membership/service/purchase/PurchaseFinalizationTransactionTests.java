package com.glofox.memberships.membership.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.plan.model.PlanType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseFinalizationTransactionTests {

    private final MembershipPurchaseRepository purchases = mock(MembershipPurchaseRepository.class);
    private final InventoryReservationService inventory = mock(InventoryReservationService.class);
    private final UnlimitedMembershipClaimService claims = mock(UnlimitedMembershipClaimService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final PurchaseFinalizationTransaction transaction =
            new PurchaseFinalizationTransaction(purchases, inventory, claims, idempotency, sagas);

    @Test
    void createsPurchaseAndCompletesAssociatedResources() {
        UUID purchaseId = UUID.randomUUID();
        PurchaseRequest request = request();
        PlanPurchaseDetails plan = new PlanPurchaseDetails(request.planId(), "Pack", PlanType.PACK, 5_000, 10, true);
        when(purchases.findById(purchaseId)).thenReturn(Optional.empty());
        when(purchases.saveAndFlush(any(MembershipPurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipPurchase result = transaction.finalizePurchase(
                purchaseId, request, "alice", plan, "txn-1", "client-key");

        assertThat(result.getId()).isEqualTo(purchaseId);
        assertThat(result.getStatus()).isEqualTo(PurchaseStatus.ACTIVE);
        assertThat(result.getPaymentTransactionId()).isEqualTo("txn-1");
        verify(inventory).commit(purchaseId);
        verify(idempotency).complete("client-key", purchaseId);
        verify(sagas).completed(purchaseId);
    }

    @Test
    void refusesExistingPurchaseWithDifferentPaymentTransaction() {
        UUID purchaseId = UUID.randomUUID();
        MembershipPurchase existing = new MembershipPurchase(purchaseId, "Alice", "alice", UUID.randomUUID(),
                "Pack", PlanType.PACK, 5_000, 10, LocalDate.now(), PurchaseStatus.ACTIVE, "txn-original", null);
        when(purchases.findById(purchaseId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transaction.finalizePurchase(purchaseId, request(), "alice",
                new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5_000, 10, false),
                "txn-different", "client-key"))
                .isInstanceOf(IllegalStateException.class);
        verify(purchases, never()).saveAndFlush(any(MembershipPurchase.class));
        verify(sagas, never()).completed(purchaseId);
    }

    private PurchaseRequest request() {
        return new PurchaseRequest("Alice", UUID.randomUUID(), LocalDate.of(2026, 11, 2));
    }
}
