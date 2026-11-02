package com.glofox.memberships.membership.repository.entity;

import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_RECONCILIATION_REQUIRED_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_RECOVERY_GRACE_SECONDS;

import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "purchase_sagas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseSaga {

    @Id
    private UUID id;

    @Column(name = "client_idempotency_key", nullable = false, unique = true, length = 255)
    private String clientIdempotencyKey;

    @Column(name = "provider_idempotency_key", nullable = false, length = 120)
    private String providerIdempotencyKey;

    @Column(name = "member_name", nullable = false, length = 160)
    private String memberName;

    @Column(name = "member_key", nullable = false, length = 160)
    private String memberKey;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "plan_name", nullable = false, length = 120)
    private String planName;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 16)
    private PlanType planType;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    private Integer credits;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "has_finite_inventory", nullable = false)
    private boolean hasFiniteInventory;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PurchaseSagaStatus status;

    @Column(name = "failure_status")
    private Integer failureStatus;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "next_recovery_at")
    private Instant nextRecoveryAt;

    @Column(name = "recovery_lease_until")
    private Instant recoveryLeaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public PurchaseSaga(
            UUID id,
            String clientIdempotencyKey,
            String providerIdempotencyKey,
            String memberName,
            String memberKey,
            PlanPurchaseDetails plan,
            PurchaseRequest request,
            boolean hasFiniteInventory,
            String correlationId) {
        this.id = id;
        this.clientIdempotencyKey = clientIdempotencyKey;
        this.providerIdempotencyKey = providerIdempotencyKey;
        this.memberName = memberName;
        this.memberKey = memberKey;
        this.planId = plan.id();
        this.planName = plan.name();
        this.planType = plan.type();
        this.priceCents = plan.priceCents();
        this.credits = plan.credits();
        this.startDate = request.startDate();
        this.hasFiniteInventory = plan.hasFiniteInventory();
        this.correlationId = correlationId;
        this.status = PurchaseSagaStatus.INITIATED;
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

    public void markValidated() {
        transition(PurchaseSagaStatus.INITIATED, PurchaseSagaStatus.VALIDATED);
        nextRecoveryAt = Instant.now().plusSeconds(PURCHASE_RECOVERY_GRACE_SECONDS);
    }

    public void markResourcesReserved() {
        transition(PurchaseSagaStatus.VALIDATED, PurchaseSagaStatus.RESOURCES_RESERVED);
        nextRecoveryAt = Instant.now().plusSeconds(PURCHASE_RECOVERY_GRACE_SECONDS);
    }

    public void markPaymentPending() {
        transition(PurchaseSagaStatus.RESOURCES_RESERVED, PurchaseSagaStatus.PAYMENT_PENDING);
        nextRecoveryAt = Instant.now().plusSeconds(PURCHASE_RECOVERY_GRACE_SECONDS);
    }

    public void markPaymentSucceeded() {
        if (status != PurchaseSagaStatus.PAYMENT_PENDING && status != PurchaseSagaStatus.PAYMENT_UNKNOWN) {
            throw invalidTransition(PurchaseSagaStatus.PAYMENT_SUCCEEDED);
        }
        status = PurchaseSagaStatus.PAYMENT_SUCCEEDED;
        nextRecoveryAt = Instant.now();
        recoveryLeaseUntil = null;
    }

    public void markPaymentUnknown(Instant retryAt, String message) {
        if (status != PurchaseSagaStatus.PAYMENT_PENDING && status != PurchaseSagaStatus.PAYMENT_UNKNOWN) {
            throw invalidTransition(PurchaseSagaStatus.PAYMENT_UNKNOWN);
        }
        status = PurchaseSagaStatus.PAYMENT_UNKNOWN;
        failureCode = PAYMENT_OUTCOME_UNKNOWN_CODE;
        failureMessage = message;
        nextRecoveryAt = retryAt;
        recoveryLeaseUntil = null;
    }

    public void beginCompensation(HttpStatus httpStatus, String code, String message) {
        if (status != PurchaseSagaStatus.PAYMENT_PENDING
                && status != PurchaseSagaStatus.PAYMENT_UNKNOWN
                && status != PurchaseSagaStatus.RESOURCES_RESERVED) {
            throw invalidTransition(PurchaseSagaStatus.COMPENSATING);
        }
        status = PurchaseSagaStatus.COMPENSATING;
        failureStatus = httpStatus.value();
        failureCode = code;
        failureMessage = message;
        nextRecoveryAt = Instant.now();
        recoveryLeaseUntil = null;
    }

    public void markCompensated() {
        transition(PurchaseSagaStatus.COMPENSATING, PurchaseSagaStatus.COMPENSATED);
        nextRecoveryAt = null;
        recoveryLeaseUntil = null;
    }

    public void markFailed(HttpStatus httpStatus, String code, String message) {
        if (status != PurchaseSagaStatus.INITIATED
                && status != PurchaseSagaStatus.VALIDATED
                && status != PurchaseSagaStatus.RESOURCES_RESERVED) {
            throw invalidTransition(PurchaseSagaStatus.FAILED);
        }
        status = PurchaseSagaStatus.FAILED;
        failureStatus = httpStatus.value();
        failureCode = code;
        failureMessage = message;
        nextRecoveryAt = null;
        recoveryLeaseUntil = null;
    }

    public void markCompleted() {
        if (status == PurchaseSagaStatus.COMPLETED) {
            return;
        }
        transition(PurchaseSagaStatus.PAYMENT_SUCCEEDED, PurchaseSagaStatus.COMPLETED);
        nextRecoveryAt = null;
        recoveryLeaseUntil = null;
    }

    public void deferRecoveryUntil(Instant retryAt) {
        if (status != PurchaseSagaStatus.INITIATED && status != PurchaseSagaStatus.VALIDATED
                && status != PurchaseSagaStatus.RESOURCES_RESERVED && status != PurchaseSagaStatus.PAYMENT_PENDING
                && status != PurchaseSagaStatus.PAYMENT_SUCCEEDED && status != PurchaseSagaStatus.PAYMENT_UNKNOWN
                && status != PurchaseSagaStatus.COMPENSATING) {
            throw new IllegalStateException("Saga is not recoverable");
        }
        nextRecoveryAt = retryAt;
        recoveryLeaseUntil = null;
    }

    public void requireManualReview(String message) {
        if (status != PurchaseSagaStatus.PAYMENT_PENDING && status != PurchaseSagaStatus.PAYMENT_UNKNOWN) {
            throw invalidTransition(PurchaseSagaStatus.MANUAL_REVIEW);
        }
        status = PurchaseSagaStatus.MANUAL_REVIEW;
        failureCode = PAYMENT_RECONCILIATION_REQUIRED_CODE;
        failureMessage = message;
        nextRecoveryAt = null;
        recoveryLeaseUntil = null;
    }

    public PlanPurchaseSnapshot planSnapshot() {
        return new PlanPurchaseSnapshot(planId, planName, planType, priceCents, credits, hasFiniteInventory);
    }

    public PurchaseRequest purchaseRequest() {
        return new PurchaseRequest(memberName, planId, startDate);
    }

    private void transition(PurchaseSagaStatus expected, PurchaseSagaStatus target) {
        if (status != expected) {
            throw invalidTransition(target);
        }
        status = target;
    }

    private IllegalStateException invalidTransition(PurchaseSagaStatus target) {
        return new IllegalStateException("Cannot move purchase saga from " + status + " to " + target);
    }

    public record PlanPurchaseSnapshot(
            UUID planId,
            String planName,
            PlanType planType,
            int priceCents,
            Integer credits,
            boolean hasFiniteInventory) {
    }
}
