package com.glofox.memberships.payment.repository;

import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    Optional<PaymentAttempt> findByPurchaseAttemptId(UUID purchaseAttemptId);
}
