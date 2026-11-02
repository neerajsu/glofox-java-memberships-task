package com.glofox.memberships.common.retry;

import static com.glofox.memberships.common.logging.LogEvents.OPTIMISTIC_LOCK_RETRY;
import static com.glofox.memberships.common.logging.LogEvents.OPTIMISTIC_LOCK_RETRY_EXHAUSTED;

import com.glofox.memberships.common.exception.OptimisticLockRetryExhaustedException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

/** Retries only local database work, with a new transaction for every attempt. */
@Service
@Slf4j
@RequiredArgsConstructor
public class OptimisticLockRetryPolicy {
    private final RetryTemplate optimisticLockRetryTemplate;
    private final PlatformTransactionManager transactionManager;

    public <T> T execute(Supplier<T> operation) {
        AtomicInteger attempts = new AtomicInteger();
        try {
            return optimisticLockRetryTemplate.execute(() -> {
                int attempt = attempts.incrementAndGet();
                try {
                    return executeInFreshTransaction(operation);
                } catch (RuntimeException failure) {
                    if (attempt < 3 && isOptimisticLockFailure(failure)) {
                        log.debug("event={} correlationId={} attempt={} nextAttempt={} maxAttempts={} exceptionType={}",
                                OPTIMISTIC_LOCK_RETRY, CorrelationIdContext.currentOr("unknown"), attempt,
                                attempt + 1, 3, failure.getClass().getSimpleName());
                    }
                    throw failure;
                }
            });
        } catch (RetryException retryFailure) {
            Throwable cause = retryFailure.getCause();
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Optimistic-lock retry was interrupted", cause);
            }
            if (isOptimisticLockFailure(cause)) {
                log.warn("event={} correlationId={} retries={} exceptionType={}",
                        OPTIMISTIC_LOCK_RETRY_EXHAUSTED, CorrelationIdContext.currentOr("unknown"),
                        retryFailure.getRetryCount(), cause.getClass().getSimpleName());
                OptimisticLockRetryExhaustedException controlled =
                        new OptimisticLockRetryExhaustedException(cause);
                for (Throwable suppressed : retryFailure.getSuppressed()) controlled.addSuppressed(suppressed);
                throw controlled;
            }
            return throwOriginal(cause);
        }
    }

    private <T> T executeInFreshTransaction(Supplier<T> operation) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setName("optimisticLockRetryAttempt");
        transaction.setTimeout(1);
        return transaction.execute(status -> operation.get());
    }

    private boolean isOptimisticLockFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> T throwOriginal(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) throw runtimeException;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Retry operation failed", failure);
    }
}
