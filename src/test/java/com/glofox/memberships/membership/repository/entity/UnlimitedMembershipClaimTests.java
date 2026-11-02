package com.glofox.memberships.membership.repository.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnlimitedMembershipClaimTests {

    @Test
    void activatesOnlyForTheClaimedPurchaseAndIsIdempotent() {
        UUID purchaseId = UUID.randomUUID();
        UnlimitedMembershipClaim claim = new UnlimitedMembershipClaim("alice", purchaseId);

        claim.activate(purchaseId);
        claim.activate(purchaseId);

        assertThat(claim.getStatus()).isEqualTo(UnlimitedClaimStatus.ACTIVE);
    }

    @Test
    void rejectsActivationForDifferentPurchase() {
        UUID originalPurchase = UUID.randomUUID();
        UnlimitedMembershipClaim claim = new UnlimitedMembershipClaim("alice", originalPurchase);

        assertThatThrownBy(() -> claim.activate(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }
}
