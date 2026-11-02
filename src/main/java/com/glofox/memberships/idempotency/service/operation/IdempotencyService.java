package com.glofox.memberships.idempotency.service.operation;

import static com.glofox.memberships.common.logging.LogEvents.IDEMPOTENCY_CONFLICT;
import static com.glofox.memberships.common.logging.LogEvents.IDEMPOTENCY_RECORD_CREATED;
import static com.glofox.memberships.idempotency.constant.Constants.IN_PROGRESS_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.INVALID_KEY_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REQUIRED_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REUSED_CODE;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.idempotency.exceptions.IdempotencyException;
import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.entity.IdempotencyRecord;
import com.glofox.memberships.idempotency.service.record.IdempotencyRecordStore;
import com.glofox.memberships.idempotency.service.request.CanonicalPurchaseRequestHasher;
import com.glofox.memberships.membership.api.PurchaseRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRecordStore store;
    private final CanonicalPurchaseRequestHasher requestHasher;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public PreparedRequest prepare(String rawKey, PurchaseRequest request) {
        return new PreparedRequest(normalizeKey(rawKey), requestHasher.hash(request));
    }

    public StartResult findExisting(PreparedRequest prepared) {
        return store.find(prepared.clientKey())
                .map(record -> resolve(record, prepared.requestHash())).orElse(null);
    }

    public StartResult createInProgress(PreparedRequest prepared, UUID operationId, String providerKey) {
        store.create(prepared.clientKey(), prepared.requestHash(), operationId, providerKey);
        log.debug("event={} correlationId={} operationId={}", IDEMPOTENCY_RECORD_CREATED,
                CorrelationIdContext.currentOr(operationId.toString()), operationId);
        return new StartResult(prepared.clientKey(), operationId, providerKey, null, null);
    }

    public StartResult createFailed(PreparedRequest prepared, UUID operationId, String providerKey, ApiException failure) {
        store.create(prepared.clientKey(), prepared.requestHash(), operationId, providerKey);
        store.fail(prepared.clientKey(), failure.getStatus(), failure.getCode(), failure.getMessage());
        return new StartResult(prepared.clientKey(), operationId, providerKey, null,
                new StoredFailure(failure.getStatus().value(), failure.getCode(), failure.getMessage()));
    }

    public void complete(String key, UUID purchaseId) {
        store.complete(key, purchaseId);
    }

    public void fail(String key, ApiException failure) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            store.fail(key, failure.getStatus(), failure.getCode(), failure.getMessage());
            return;
        }
        optimisticLockRetryPolicy.execute(() -> {
            store.fail(key, failure.getStatus(), failure.getCode(), failure.getMessage());
            return null;
        });
    }

    public void failIfInProgress(String key, ApiException failure) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            store.failIfInProgress(key, failure.getStatus(), failure.getCode(), failure.getMessage());
            return;
        }
        optimisticLockRetryPolicy.execute(() -> {
            store.failIfInProgress(key, failure.getStatus(), failure.getCode(), failure.getMessage());
            return null;
        });
    }

    private StartResult resolve(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            log.warn("event={} correlationId={} operationId={} conflictType=request_mismatch",
                    IDEMPOTENCY_CONFLICT, CorrelationIdContext.currentOr(record.getOperationId().toString()),
                    record.getOperationId());
            throw new IdempotencyException(
                    HttpStatus.CONFLICT,
                    KEY_REUSED_CODE,
                    "The idempotency key was already used with a different request");
        }

        return switch (record.getStatus()) {
            case COMPLETED -> new StartResult(record.getClientKey(), record.getOperationId(),
                    record.getProviderIdempotencyKey(), record.getPurchaseId(), null);
            case FAILED -> new StartResult(record.getClientKey(), record.getOperationId(),
                    record.getProviderIdempotencyKey(), null,
                    new StoredFailure(record.getFailureStatus(), record.getFailureCode(), record.getFailureMessage()));
            case IN_PROGRESS -> {
                log.info("event={} correlationId={} operationId={} conflictType=request_in_progress",
                        IDEMPOTENCY_CONFLICT, CorrelationIdContext.currentOr(record.getOperationId().toString()),
                        record.getOperationId());
                throw new IdempotencyException(HttpStatus.CONFLICT, IN_PROGRESS_CODE,
                        "A purchase with this idempotency key is still processing");
            }
        };
    }

    private String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IdempotencyException(HttpStatus.BAD_REQUEST, KEY_REQUIRED_CODE, "Idempotency-Key header is required");
        }
        String key = rawKey.trim();
        if (key.length() > 255) {
            throw new IdempotencyException(HttpStatus.BAD_REQUEST, INVALID_KEY_CODE, "Idempotency-Key must be at most 255 characters");
        }
        return key;
    }

    public record StartResult(
            String clientKey,
            UUID operationId,
            String providerKey,
            UUID completedPurchaseId,
            StoredFailure failure) {
    }

    public record StoredFailure(int status, String code, String message) {
    }

    public record PreparedRequest(String clientKey, String requestHash) {}
}
