package com.glofox.memberships.idempotency.repository.entity;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Column(name = "client_key", length = 255)
    private String clientKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "operation_id", nullable = false, unique = true)
    private UUID operationId;

    @Column(name = "provider_idempotency_key", nullable = false, unique = true, length = 120)
    private String providerIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdempotencyStatus status;

    @Column(name = "purchase_id")
    private UUID purchaseId;

    @Column(name = "failure_status")
    private Integer failureStatus;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public IdempotencyRecord(String clientKey, String requestHash, UUID operationId, String providerIdempotencyKey) {
        this.clientKey = clientKey;
        this.requestHash = requestHash;
        this.operationId = operationId;
        this.providerIdempotencyKey = providerIdempotencyKey;
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    @PrePersist
    void setCreatedAndUpdatedAt() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void setUpdatedAt() {
        updatedAt = Instant.now();
    }

    public void complete(UUID completedPurchaseId) {
        if (status == IdempotencyStatus.COMPLETED && completedPurchaseId.equals(purchaseId)) {
            return;
        }
        ensureInProgress();
        purchaseId = completedPurchaseId;
        status = IdempotencyStatus.COMPLETED;
    }

    public void fail(HttpStatus httpStatus, String code, String message) {
        if (status == IdempotencyStatus.FAILED
                && failureStatus == httpStatus.value()
                && java.util.Objects.equals(failureCode, code)
                && java.util.Objects.equals(failureMessage, message)) {
            return;
        }
        ensureInProgress();
        failureStatus = httpStatus.value();
        failureCode = code;
        failureMessage = message;
        status = IdempotencyStatus.FAILED;
    }

    private void ensureInProgress() {
        if (status != IdempotencyStatus.IN_PROGRESS) {
            throw new IllegalStateException("Idempotency record is already terminal");
        }
    }
}
