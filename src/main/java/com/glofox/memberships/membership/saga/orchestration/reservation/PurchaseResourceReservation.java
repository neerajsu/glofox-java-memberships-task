package com.glofox.memberships.membership.saga.orchestration.reservation;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchaseResourceReservation {
    private final PurchaseResourceReservationTransaction transaction;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public void reserve(UUID id, String memberKey, PlanPurchaseDetails plan) {
        optimisticLockRetryPolicy.execute(() -> {
            transaction.reserve(id, memberKey, plan);
            return null;
        });
    }
}
