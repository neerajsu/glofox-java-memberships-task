package com.glofox.memberships.membership.repository;

import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import com.glofox.memberships.membership.repository.entity.UnlimitedMembershipClaim;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnlimitedMembershipClaimRepository extends JpaRepository<UnlimitedMembershipClaim, String> {

    long deleteByMemberKeyAndPurchaseIdAndStatus(
            String memberKey,
            java.util.UUID purchaseId,
            UnlimitedClaimStatus status);
}
