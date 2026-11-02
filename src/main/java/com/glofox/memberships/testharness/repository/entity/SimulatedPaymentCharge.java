package com.glofox.memberships.testharness.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_harness_payment_charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulatedPaymentCharge {
    @Id private UUID id;
    @Column(name = "provider_key", nullable = false, unique = true, length = 120) private String providerKey;
    @Column(name = "transaction_id", nullable = false, unique = true, length = 120) private String transactionId;
    @Column(name = "amount_cents", nullable = false) private int amountCents;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    public SimulatedPaymentCharge(String providerKey, String transactionId, int amountCents) {
        id = UUID.randomUUID(); this.providerKey = providerKey; this.transactionId = transactionId; this.amountCents = amountCents;
    }
    @PrePersist void timestamp() { createdAt = Instant.now(); }
}
