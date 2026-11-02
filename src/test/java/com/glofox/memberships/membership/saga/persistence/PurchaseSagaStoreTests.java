package com.glofox.memberships.membership.saga.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseSagaStoreTests {

    private final PurchaseSagaRepository repository = mock(PurchaseSagaRepository.class);
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final PurchaseSagaStore store = new PurchaseSagaStore(repository, retry, new com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties());

    @Test
    void createPersistsValidatedSagaSnapshot() {
        UUID operationId = UUID.randomUUID();
        PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5_000, 10, true);
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any(PurchaseSaga.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        store.create(operationId, "client-key", "provider-key",
                new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2)), "alice", plan, true, "cid");

        org.mockito.ArgumentCaptor<PurchaseSaga> captor = org.mockito.ArgumentCaptor.forClass(PurchaseSaga.class);
        org.mockito.Mockito.verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PurchaseSagaStatus.VALIDATED);
        assertThat(captor.getValue().getProviderIdempotencyKey()).isEqualTo("provider-key");
    }

    @Test
    void paymentConfirmedOnlyForSuccessfulOrCompletedSaga() {
        UUID operationId = UUID.randomUUID();
        PurchaseSaga saga = saga(operationId);
        when(repository.findById(operationId)).thenReturn(Optional.of(saga));

        assertThat(store.paymentConfirmed(operationId)).isFalse();
        saga.markValidated();
        saga.markResourcesReserved();
        saga.markPaymentPending();
        saga.markPaymentSucceeded();
        assertThat(store.paymentConfirmed(operationId)).isTrue();
    }

    @Test
    void findByIdReturnsControlledNotFoundForUnknownOperation() {
        UUID operationId = UUID.randomUUID();
        when(repository.findById(operationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> store.findById(operationId)).isInstanceOf(MembershipException.class);
    }

    private PurchaseSaga saga(UUID id) {
        PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5_000, 10, false);
        return new PurchaseSaga(id, "client-key", "provider-key", "Alice", "alice", plan,
                new PurchaseRequest("Alice", plan.id(), LocalDate.of(2026, 11, 2)), false, "cid");
    }
}
