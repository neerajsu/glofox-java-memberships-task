package com.glofox.memberships.payment.service;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentAttemptStore {

    private final PaymentAttemptRepository repository;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    @Transactional(timeout = 1)
    public PaymentAttempt create(UUID purchaseAttemptId, String providerKey, String memberName, int amountCents,
            String correlationId) {
        return repository.saveAndFlush(new PaymentAttempt(
                UUID.randomUUID(), purchaseAttemptId, providerKey, memberName, amountCents, correlationId));
    }

    @Transactional(readOnly = true, timeout = 1)
    public PaymentAttempt findByPurchaseAttemptId(UUID purchaseAttemptId) {
        return repository.findByPurchaseAttemptId(purchaseAttemptId)
                .orElseThrow(() -> new IllegalStateException("Payment attempt was not found"));
    }

    @Transactional(readOnly = true, timeout = 1)
    public Optional<PaymentAttempt> findOptionalByPurchaseAttemptId(UUID purchaseAttemptId) {
        return repository.findByPurchaseAttemptId(purchaseAttemptId);
    }

    public PaymentAttempt startAttempt(UUID purchaseAttemptId) {
        return optimisticLockRetryPolicy.execute(() -> {
            PaymentAttempt attempt = repository.findByPurchaseAttemptId(purchaseAttemptId)
                    .orElseThrow(() -> new IllegalStateException("Payment attempt was not found"));
            attempt.startAttempt();
            return attempt;
        });
    }

    public void recordRetryableFailure(UUID purchaseAttemptId, com.glofox.memberships.payment.model.FailureType type, String message) {
        optimisticLockRetryPolicy.execute(() -> {
            repository.findByPurchaseAttemptId(purchaseAttemptId)
                    .orElseThrow(() -> new IllegalStateException("Payment attempt was not found"))
                    .recordRetryableFailure(type, message);
            return null;
        });
    }
}
