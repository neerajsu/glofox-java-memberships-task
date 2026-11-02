package com.glofox.memberships.membership.service.purchase;

import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_FAILED;
import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_REPLAYED;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StoredFailure;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.dto.PurchaseResponse;
import com.glofox.memberships.membership.saga.orchestration.PurchaseSagaOrchestrator;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryTransaction;
import com.glofox.memberships.membership.service.query.PurchaseQueryService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseService {

    private final IdempotencyService idempotencyService;
    private final PurchaseQueryService purchaseQueryService;
    private final PurchaseSagaOrchestrator sagaOrchestrator;
    private final PurchaseSagaStore sagaStore;
    private final PurchaseInitializationService initializationService;
    private final PurchaseSagaRecoveryTransaction terminalFailureTransaction;

    public PurchaseResponse purchase(PurchaseRequest request, String idempotencyKey) {
        PurchaseInitializationService.InitializedPurchase initialized =
                initializationService.initialize(idempotencyKey, request);
        IdempotencyService.StartResult operation = initialized.operation();
        if (operation.completedPurchaseId() != null) {
            log.debug("event={} correlationId={} operationId={} purchaseId={}", PURCHASE_REPLAYED,
                    CorrelationIdContext.currentOr(operation.operationId().toString()), operation.operationId(),
                    operation.completedPurchaseId());
            return purchaseQueryService.findById(operation.completedPurchaseId());
        }
        StoredFailure storedFailure = operation.failure();
        if (storedFailure != null) {
            log.debug("event={} correlationId={} operationId={} errorCode={} replay=true", PURCHASE_FAILED,
                    CorrelationIdContext.currentOr(operation.operationId().toString()), operation.operationId(),
                    storedFailure.code());
            throw new ApiException(
                    HttpStatus.valueOf(storedFailure.status()),
                    storedFailure.code(),
                    storedFailure.message());
        }

        try {
            return sagaOrchestrator.execute(request, operation, initialized.plan());
        } catch (ApiException failure) {
            if (failure.getStatus().is5xxServerError()) {
                log.warn("event={} correlationId={} operationId={} status={} errorCode={}", PURCHASE_FAILED,
                        CorrelationIdContext.currentOr(operation.operationId().toString()), operation.operationId(),
                        failure.getStatus().value(), failure.getCode());
            } else {
                log.info("event={} correlationId={} operationId={} status={} errorCode={}", PURCHASE_FAILED,
                        CorrelationIdContext.currentOr(operation.operationId().toString()), operation.operationId(),
                        failure.getStatus().value(), failure.getCode());
            }
            if (sagaStore.isBeforeResourceReservation(operation.operationId())) {
                terminalFailureTransaction.failBeforeResources(operation.operationId(), failure);
            } else if (sagaStore.hasTerminalFailure(operation.operationId())) {
                idempotencyService.fail(operation.clientKey(), failure);
            }
            throw failure;
        }
    }

}
