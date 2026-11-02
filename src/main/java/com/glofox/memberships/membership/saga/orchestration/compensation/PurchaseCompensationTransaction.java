package com.glofox.memberships.membership.saga.orchestration.compensation;

import static com.glofox.memberships.membership.constant.Constants.PAYMENT_DECLINED_CODE;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseCompensationTransaction {
    private final InventoryReservationService inventory;
    private final UnlimitedMembershipClaimService claims;
    private final PurchaseSagaStore sagas;
    private final IdempotencyService idempotency;

    @Transactional
    public void compensate(UUID operationId) {
        PurchaseSaga saga = sagas.findById(operationId);
        if (saga.isHasFiniteInventory()) inventory.release(operationId);
        if (saga.getPlanType() == PlanType.UNLIMITED) claims.releasePending(saga.getMemberKey(), operationId);
        if (saga.getStatus() == PurchaseSagaStatus.COMPENSATING) sagas.compensated(operationId);
        int status = saga.getFailureStatus() == null ? HttpStatus.PAYMENT_REQUIRED.value() : saga.getFailureStatus();
        String code = saga.getFailureCode() == null ? PAYMENT_DECLINED_CODE : saga.getFailureCode();
        String message = saga.getFailureMessage() == null ? "The payment was declined" : saga.getFailureMessage();
        idempotency.failIfInProgress(saga.getClientIdempotencyKey(),
                new ApiException(HttpStatus.valueOf(status), code, message));
    }
}
