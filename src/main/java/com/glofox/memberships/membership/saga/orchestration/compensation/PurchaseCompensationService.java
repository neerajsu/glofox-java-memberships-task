package com.glofox.memberships.membership.saga.orchestration.compensation;

import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_COMPENSATION_COMPLETED;
import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_COMPENSATION_STARTED;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseCompensationService {
    private final PurchaseCompensationTransaction transaction;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public void compensate(PurchaseSaga saga) {
        UUID operationId = saga.getId();
        log.info("event={} correlationId={} operationId={}", PURCHASE_COMPENSATION_STARTED,
                saga.getCorrelationId(), operationId);
        optimisticLockRetryPolicy.execute(() -> {
            transaction.compensate(operationId);
            return null;
        });
        log.info("event={} correlationId={} operationId={}", PURCHASE_COMPENSATION_COMPLETED,
                saga.getCorrelationId(), operationId);
    }
}
