package com.glofox.memberships.membership.saga.persistence;

import static com.glofox.memberships.membership.constant.Constants.PURCHASE_SAGA_NOT_FOUND_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_SAGA_RECOVERABLE_STATUSES;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PurchaseSagaStore {

    private final PurchaseSagaRepository repository;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;
    private final com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties recoveryProperties;

    @Transactional(timeout = 1)
    public void create(
            UUID operationId,
            String clientKey,
            String providerKey,
            PurchaseRequest request,
            String memberKey,
            PlanPurchaseDetails plan,
            boolean finiteInventory,
            String correlationId) {
        PurchaseSaga saga = repository.saveAndFlush(new PurchaseSaga(
                operationId,
                clientKey,
                providerKey,
                request.memberName(),
                memberKey,
                plan,
                request,
                finiteInventory,
                correlationId));
        saga.markValidated();
    }

    @Transactional(timeout = 1)
    public void resourcesReserved(UUID id) {
        find(id).markResourcesReserved();
    }

    @Transactional(timeout = 1)
    public void paymentPending(UUID id) {
        find(id).markPaymentPending();
    }

    @Transactional(timeout = 1)
    public void beginCompensation(UUID id, ApiException failure) {
        find(id).beginCompensation(failure.getStatus(), failure.getCode(), failure.getMessage());
    }

    @Transactional(timeout = 1)
    public void compensated(UUID id) {
        find(id).markCompensated();
    }

    @Transactional(timeout = 1)
    public void completed(UUID id) {
        find(id).markCompleted();
    }

    public void deferRecovery(UUID id, Instant retryAt) {
        optimisticLockRetryPolicy.execute(() -> {
            find(id).deferRecoveryUntil(retryAt);
            return null;
        });
    }

    public void manualReview(UUID id, String message) {
        optimisticLockRetryPolicy.execute(() -> {
            find(id).requireManualReview(message);
            return null;
        });
    }

    @Transactional(readOnly = true, timeout = 1)
    public PurchaseSaga findById(UUID id) {
        return find(id);
    }

    @Transactional(readOnly = true, timeout = 1)
    public boolean paymentConfirmed(UUID id) {
        return repository.findById(id).map(saga -> saga.getStatus() == PurchaseSagaStatus.PAYMENT_SUCCEEDED
                || saga.getStatus() == PurchaseSagaStatus.COMPLETED).orElse(false);
    }

    @Transactional(readOnly = true, timeout = 1)
    public boolean hasTerminalFailure(UUID id) {
        return repository.findById(id).map(saga ->
                saga.getStatus() == PurchaseSagaStatus.FAILED
                        || saga.getStatus() == PurchaseSagaStatus.COMPENSATED).orElse(false);
    }

    @Transactional(readOnly = true, timeout = 1)
    public boolean isBeforeResourceReservation(UUID id) {
        return repository.findById(id).map(saga ->
                saga.getStatus() == PurchaseSagaStatus.INITIATED
                        || saga.getStatus() == PurchaseSagaStatus.VALIDATED).orElse(false);
    }

    @Transactional(readOnly = true, timeout = 1)
    public List<PurchaseSaga> findRecoverable(Instant now, int batchSize) {
        return repository.findRecoverable(PURCHASE_SAGA_RECOVERABLE_STATUSES, now, now.minus(recoveryProperties.getMinimumAge()), PageRequest.of(0, batchSize));
    }

    @Transactional(timeout = 1)
    public boolean claimForRecovery(PurchaseSaga candidate, Instant now, Instant leaseUntil) {
        return repository.claimForRecovery(
                candidate.getId(),
                candidate.getStatus(),
                candidate.getVersion(),
                now,
                now.minus(recoveryProperties.getMinimumAge()),
                leaseUntil) == 1;
    }

    private PurchaseSaga find(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new MembershipException(HttpStatus.NOT_FOUND, PURCHASE_SAGA_NOT_FOUND_CODE, "Purchase operation was not found"));
    }
}
