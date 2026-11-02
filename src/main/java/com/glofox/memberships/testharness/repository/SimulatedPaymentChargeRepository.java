package com.glofox.memberships.testharness.repository;

import com.glofox.memberships.testharness.repository.entity.SimulatedPaymentCharge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulatedPaymentChargeRepository extends JpaRepository<SimulatedPaymentCharge, UUID> {
    Optional<SimulatedPaymentCharge> findByProviderKey(String providerKey);
}
