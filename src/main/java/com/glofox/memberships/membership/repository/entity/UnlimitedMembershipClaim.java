package com.glofox.memberships.membership.repository.entity;

import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "unlimited_membership_claims")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnlimitedMembershipClaim implements Persistable<String> {

    @Id
    @Column(name = "member_key", length = 160)
    private String memberKey;

    @Column(name = "purchase_id", nullable = false, unique = true)
    private UUID purchaseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UnlimitedClaimStatus status;

    @Transient
    private boolean newEntity = true;

    public UnlimitedMembershipClaim(String memberKey, UUID purchaseId) {
        this.memberKey = memberKey;
        this.purchaseId = purchaseId;
        this.status = UnlimitedClaimStatus.PENDING;
    }

    @Override
    public String getId() {
        return memberKey;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newEntity = false;
    }

    public void activate(UUID expectedPurchaseId) {
        if (purchaseId.equals(expectedPurchaseId) && status == UnlimitedClaimStatus.ACTIVE) {
            return;
        }
        if (!purchaseId.equals(expectedPurchaseId) || status != UnlimitedClaimStatus.PENDING) {
            throw new IllegalStateException("Unlimited membership claim is not pending for this purchase");
        }
        status = UnlimitedClaimStatus.ACTIVE;
    }
}
