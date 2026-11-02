package com.glofox.memberships.membership.saga.orchestration.payment;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchasePaymentPreparation {
    private final PaymentAttemptRepository attempts;
    private final PurchaseSagaStore sagas;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public void prepare(UUID operationId, String providerKey, String memberName, int amountCents, String correlationId) {
        optimisticLockRetryPolicy.execute(() -> {
            attempts.saveAndFlush(new PaymentAttempt(UUID.randomUUID(), operationId, providerKey, memberName, amountCents, correlationId));
            sagas.paymentPending(operationId);
            return null;
        });
    }
}
