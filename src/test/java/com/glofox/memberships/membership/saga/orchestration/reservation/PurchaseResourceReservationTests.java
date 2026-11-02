package com.glofox.memberships.membership.saga.orchestration.reservation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseResourceReservationTests {
    private final PurchaseResourceReservationTransaction transaction = mock(PurchaseResourceReservationTransaction.class);
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final PurchaseResourceReservation reservation = new PurchaseResourceReservation(transaction, retry);
    private final UUID id = UUID.randomUUID();
    private final PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5000, 10, true);

    @BeforeEach
    void executeRetryCallbackInline() {
        when(retry.execute(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void executesAtomicCollaboratorWithinRetryCallback() {
        reservation.reserve(id, "alice", plan);
        verify(transaction).reserve(id, "alice", plan);
        verify(retry).execute(any());
    }

    @Test
    void propagatesFailureFromTransaction() {
        var failure = new IllegalStateException("reservation failed");
        doThrow(failure).when(transaction).reserve(id, "alice", plan);
        assertThatThrownBy(() -> reservation.reserve(id, "alice", plan)).isSameAs(failure);
    }
}
