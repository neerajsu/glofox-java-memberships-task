package com.glofox.memberships.idempotency.service.record;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.entity.IdempotencyRecord;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdempotencyRecordStore {

    private final IdempotencyRecordRepository repository;

    @Transactional
    public void create(String key, String requestHash, UUID operationId, String providerKey) {
        repository.saveAndFlush(new IdempotencyRecord(key, requestHash, operationId, providerKey));
    }

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return repository.findById(key);
    }

    @Transactional
    public void complete(String key, UUID purchaseId) {
        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record was not found"));
        record.complete(purchaseId);
    }

    @Transactional
    public void fail(String key, HttpStatus status, String code, String message) {
        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record was not found"));
        record.fail(status, code, message);
    }

    @Transactional
    public void failIfInProgress(String key, HttpStatus status, String code, String message) {
        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record was not found"));
        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            record.fail(status, code, message);
        }
    }
}
