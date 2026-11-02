package com.glofox.memberships.common.retry;

import static com.glofox.memberships.common.constant.Constants.OPTIMISTIC_LOCK_RETRY_EXHAUSTED_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.common.exception.OptimisticLockRetryExhaustedException;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import jakarta.persistence.OptimisticLockException;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
class OptimisticLockRetryPolicyTests {
    @Autowired OptimisticLockRetryPolicy retryPolicy;
    @Autowired PaymentAttemptRepository attempts;

    @Test
    void retriesBothSupportedExceptionTypesInFreshTransactions() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger completedTransactions = new AtomicInteger();

        String result = retryPolicy.execute(() -> {
            int attempt = calls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(completedTransactions.get()).isEqualTo(attempt - 1);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completedTransactions.incrementAndGet(); }
            });
            if (attempt == 1) throw new ObjectOptimisticLockingFailureException(PaymentAttempt.class, UUID.randomUUID());
            if (attempt == 2) throw new OptimisticLockException("concurrent update");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(completedTransactions).hasValue(3);
    }

    @Test
    void doesNotRetryArbitraryExceptions() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retryPolicy.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("business validation");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesOptimisticLockFailureWrappedInAnotherRuntimeException() {
        AtomicInteger calls = new AtomicInteger();

        String result = retryPolicy.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transaction wrapper",
                        new ObjectOptimisticLockingFailureException(PaymentAttempt.class, UUID.randomUUID()));
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotRetryPaymentDeclinesOrProviderFailures() {
        for (RuntimeException exception : java.util.List.of(
                new PaymentDeclinedException("declined"),
                new PaymentProviderException(FailureType.TRANSIENT, "provider unavailable"))) {
            AtomicInteger calls = new AtomicInteger();
            assertThatThrownBy(() -> retryPolicy.execute(() -> {
                calls.incrementAndGet();
                throw exception;
            })).isSameAs(exception);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void returnsControlledConflictAfterThreeAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retryPolicy.execute(() -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException(PaymentAttempt.class, UUID.randomUUID());
        })).isInstanceOf(OptimisticLockRetryExhaustedException.class)
                .hasMessageContaining("state kept changing")
                .satisfies(exhausted -> {
                    OptimisticLockRetryExhaustedException retryFailure = (OptimisticLockRetryExhaustedException) exhausted;
                    assertThat(retryFailure.getStatus().value()).isEqualTo(409);
                    assertThat(retryFailure.getCode()).isEqualTo(OPTIMISTIC_LOCK_RETRY_EXHAUSTED_CODE);
                });

        assertThat(calls).hasValue(3);
    }

    @Test
    void concurrentRetriesReloadAndPreserveBothAttemptCountIncrements() throws Exception {
        UUID operationId = UUID.randomUUID();
        attempts.saveAndFlush(new PaymentAttempt(UUID.randomUUID(), operationId, "retry-test-" + operationId,
                "Retry User", 1000, "retry-correlation"));
        CyclicBarrier firstAttemptBarrier = new CyclicBarrier(2);
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ConcurrentLinkedQueue<Long> loadedVersions = new ConcurrentLinkedQueue<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                AtomicInteger localCalls = new AtomicInteger();
                try {
                    retryPolicy.execute(() -> {
                        int call = localCalls.incrementAndGet();
                        callbackCalls.incrementAndGet();
                        PaymentAttempt current = attempts.findByPurchaseAttemptId(operationId).orElseThrow();
                        loadedVersions.add(current.getVersion());
                        if (call == 1) {
                            try { firstAttemptBarrier.await(); }
                            catch (Exception ex) { throw new IllegalStateException(ex); }
                        }
                        current.startAttempt();
                        return null;
                    });
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            })).toList();
            for (var task : tasks) task.get();
        }

        assertThat(failure.get()).isNull();
        assertThat(callbackCalls.get()).isEqualTo(3);
        assertThat(loadedVersions.stream().sorted().toList()).containsExactly(0L, 0L, 1L);
        assertThat(attempts.findByPurchaseAttemptId(operationId).orElseThrow().getAttemptCount()).isEqualTo(2);
    }
}
