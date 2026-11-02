package com.glofox.memberships.membership.saga.orchestration;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.dto.PurchaseResponse;
import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaFlowFactory;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.membership.service.purchase.PurchaseFinalizer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseSagaOrchestrator {
    private final PurchaseSagaFlowFactory flowFactory;
    private final PurchaseFinalizer finalizer;

    public PurchaseResponse execute(PurchaseRequest request, StartResult operation, PlanPurchaseDetails plan) {
        return flowFactory.start(operation, request, plan)
                .reserveResources()
                .preparePayment()
                .chargePayment()
                .finalizePurchase();
    }

    // Recovery enters here with a persisted confirmed payment; it never repeats the charge step.
    public PurchaseResponse finalizePurchase(UUID id, PurchaseRequest request, String memberKey,
            PlanPurchaseDetails plan, String transactionId, String clientKey) {
        return PurchaseResponse.from(finalizer.finalizePurchase(
                id, request, memberKey, plan, transactionId, clientKey));
    }
}
