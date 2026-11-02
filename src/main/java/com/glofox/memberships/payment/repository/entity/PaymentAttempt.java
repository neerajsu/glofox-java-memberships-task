package com.glofox.memberships.payment.repository.entity;

import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "payment_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAttempt {

    @Id
    private UUID id;

    @Column(name = "purchase_attempt_id", nullable = false, unique = true)
    private UUID purchaseAttemptId;

    @Column(name = "provider_idempotency_key", nullable = false, unique = true, length = 120)
    private String providerIdempotencyKey;

    @Column(name = "member_name", nullable = false, length = 160)
    private String memberName;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_failure_type", length = 16)
    private FailureType lastFailureType;

    @Column(name = "provider_transaction_id", length = 120)
    private String providerTransactionId;

    @Column(name = "last_failure_message", length = 500)
    private String lastFailureMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public PaymentAttempt(
            UUID id,
            UUID purchaseAttemptId,
            String providerIdempotencyKey,
            String memberName,
            int amountCents,
            String correlationId) {
        this.id = id;
        this.purchaseAttemptId = purchaseAttemptId;
        this.providerIdempotencyKey = providerIdempotencyKey;
        this.memberName = memberName;
        this.amountCents = amountCents;
        this.correlationId = correlationId;
        this.status = PaymentAttemptStatus.CREATED;
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

    public void startAttempt() {
        if (status != PaymentAttemptStatus.CREATED
                && status != PaymentAttemptStatus.PROCESSING
                && status != PaymentAttemptStatus.UNKNOWN) {
            throw new IllegalStateException("Payment attempt is not retryable from " + status);
        }
        attemptCount++;
        status = PaymentAttemptStatus.PROCESSING;
        lastFailureType = null;
        lastFailureMessage = null;
    }

    public void recordRetryableFailure(FailureType failureType, String message) {
        status = PaymentAttemptStatus.PROCESSING;
        lastFailureType = failureType;
        lastFailureMessage = message;
    }

    public void markSucceeded(String transactionId) {
        status = PaymentAttemptStatus.SUCCEEDED;
        providerTransactionId = transactionId;
        lastFailureType = null;
        lastFailureMessage = null;
    }

    public void markDeclined(String message) {
        status = PaymentAttemptStatus.DECLINED;
        lastFailureMessage = message;
    }

    public void markFailed(FailureType failureType, String message) {
        status = PaymentAttemptStatus.FAILED;
        lastFailureType = failureType;
        lastFailureMessage = message;
    }

    public void markUnknown(String message) {
        status = PaymentAttemptStatus.UNKNOWN;
        lastFailureType = FailureType.UNKNOWN;
        lastFailureMessage = message;
    }
}
