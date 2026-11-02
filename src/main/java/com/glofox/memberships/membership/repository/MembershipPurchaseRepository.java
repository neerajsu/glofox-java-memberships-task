package com.glofox.memberships.membership.repository;

import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipPurchaseRepository extends JpaRepository<MembershipPurchase, UUID> {
}
