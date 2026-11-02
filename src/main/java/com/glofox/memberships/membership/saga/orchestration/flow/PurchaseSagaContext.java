package com.glofox.memberships.membership.saga.orchestration.flow;

import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;

/** Stable per-purchase data; discovered payment values belong to the individual flow. */
public record PurchaseSagaContext(
        StartResult operation,
        PurchaseRequest request,
        PlanPurchaseDetails plan,
        String memberKey,
        String correlationId) {
}
