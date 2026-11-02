package com.glofox.memberships.membership.saga.recovery;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseSagaRecoveryTransaction {
    private final PurchaseSagaStore sagas;
    private final IdempotencyService idempotency;

    @Transactional
    public void failBeforeResources(UUID operationId, ApiException failure) {
        PurchaseSaga saga = sagas.findById(operationId);
        saga.markFailed(failure.getStatus(), failure.getCode(), failure.getMessage());
        idempotency.failIfInProgress(saga.getClientIdempotencyKey(), failure);
    }
}
