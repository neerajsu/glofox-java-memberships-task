package com.glofox.memberships.membership.repository.entity;

import com.glofox.memberships.membership.model.PurchaseStatus;
import com.glofox.memberships.plan.model.PlanType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "membership_purchases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MembershipPurchase {

    @Id
    private UUID id;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PurchaseStatus status;

    @Column(name = "payment_transaction_id", nullable = false, length = 120)
    private String paymentTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void setCreatedAtIfMissing() {
        if (createdAt == null) {
            // Match persisted timestamp precision so creation, GET and replay return the same snapshot.
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

}
