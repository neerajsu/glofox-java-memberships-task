package com.glofox.memberships.membership.service.purchase;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.PreparedRequest;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates idempotency and saga state in one database transaction. */
@Service
@RequiredArgsConstructor
public class PurchaseInitializationTransaction {

    private final IdempotencyService idempotency;
    private final PurchaseSagaStore sagas;

    @Transactional
    public StartResult createInProgress(
            PreparedRequest prepared,
            UUID operationId,
            String providerKey,
            PurchaseRequest request,
            String memberKey,
            PlanPurchaseDetails plan,
            String correlationId) {
        StartResult result = idempotency.createInProgress(prepared, operationId, providerKey);
        sagas.create(operationId, prepared.clientKey(), providerKey, request, memberKey, plan,
                plan.hasFiniteInventory(), correlationId);
        return result;
    }

    @Transactional
    public StartResult createFailed(
            PreparedRequest prepared, UUID operationId, String providerKey, ApiException failure) {
        return idempotency.createFailed(prepared, operationId, providerKey, failure);
    }
}
