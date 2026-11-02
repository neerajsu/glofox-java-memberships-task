package com.glofox.memberships.membership.service.purchase;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseStatus;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseFinalizationTransaction {
    private final MembershipPurchaseRepository purchaseRepository;
    private final InventoryReservationService inventoryReservationService;
    private final UnlimitedMembershipClaimService claimService;
    private final IdempotencyService idempotencyService;
    private final PurchaseSagaStore sagaStore;

    @Transactional
    public MembershipPurchase finalizePurchase(
            UUID purchaseId,
            PurchaseRequest request,
            String memberKey,
            PlanPurchaseDetails plan,
            String transactionId,
            String idempotencyKey) {
        MembershipPurchase purchase = purchaseRepository.findById(purchaseId).orElse(null);
        if (purchase == null) {
            purchase = purchaseRepository.saveAndFlush(new MembershipPurchase(
                    purchaseId,
                    request.memberName(),
                    memberKey,
                    plan.id(),
                    plan.name(),
                    plan.type(),
                    plan.priceCents(),
                    plan.credits(),
                    request.startDate(),
                    PurchaseStatus.ACTIVE,
                    transactionId,
                    null));
        } else if (!transactionId.equals(purchase.getPaymentTransactionId())) {
            throw new IllegalStateException("Purchase already exists with a different payment transaction");
        }

        if (plan.type() == PlanType.UNLIMITED) claimService.activate(memberKey, purchaseId);
        if (plan.hasFiniteInventory()) inventoryReservationService.commit(purchaseId);
        idempotencyService.complete(idempotencyKey, purchaseId);
        sagaStore.completed(purchaseId);
        return purchase;
    }
}
