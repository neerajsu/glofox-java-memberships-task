package com.glofox.memberships.membership.service.purchase;

import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_FINALIZED;

import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseFinalizer {
    private final PurchaseFinalizationTransaction finalizationTransaction;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public MembershipPurchase finalizePurchase(
            UUID purchaseId,
            PurchaseRequest request,
            String memberKey,
            PlanPurchaseDetails plan,
            String transactionId,
            String idempotencyKey) {
        MembershipPurchase purchase = optimisticLockRetryPolicy.execute(() -> finalizationTransaction.finalizePurchase(
                purchaseId, request, memberKey, plan, transactionId, idempotencyKey));
        log.info("event={} correlationId={} operationId={} purchaseId={}", PURCHASE_FINALIZED,
                CorrelationIdContext.currentOr(purchaseId.toString()), purchaseId, purchase.getId());
        return purchase;
    }
}
