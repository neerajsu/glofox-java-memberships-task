package com.glofox.memberships.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PaymentAttemptStoreTests {

    private final PaymentAttemptRepository repository = mock(PaymentAttemptRepository.class);
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final PaymentAttemptStore store = new PaymentAttemptStore(repository, retry);

    @Test
    void createsAttemptWithStableProviderKeyAndInitialStatus() {
        when(repository.saveAndFlush(any(PaymentAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAttempt attempt = store.create(UUID.randomUUID(), "provider-key", "Alice", 5_000, "cid");

        assertThat(attempt.getProviderIdempotencyKey()).isEqualTo("provider-key");
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.CREATED);
    }

    @Test
    void startsAttemptInsideRetryPolicy() {
        UUID operationId = UUID.randomUUID();
        PaymentAttempt attempt = new PaymentAttempt(UUID.randomUUID(), operationId, "provider-key", "Alice", 5_000, "cid");
        when(repository.findByPurchaseAttemptId(operationId)).thenReturn(Optional.of(attempt));
        when(retry.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<PaymentAttempt> action = invocation.getArgument(0);
            return action.get();
        });

        assertThat(store.startAttempt(operationId)).isSameAs(attempt);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(attempt.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void reportsMissingAttemptClearly() {
        UUID operationId = UUID.randomUUID();
        when(repository.findByPurchaseAttemptId(operationId)).thenReturn(Optional.empty());
        when(retry.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<Object> action = invocation.getArgument(0);
            return action.get();
        });

        assertThatThrownBy(() -> store.startAttempt(operationId)).isInstanceOf(IllegalStateException.class);
    }
}
