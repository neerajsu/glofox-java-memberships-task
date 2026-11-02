package com.glofox.memberships.membership.service.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.membership.repository.entity.UnlimitedMembershipClaim;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnlimitedClaimActivationTests {

    @Test
    void activatesPendingClaimForTheExpectedPurchase() {
        UnlimitedMembershipClaimRepository repository = mock(UnlimitedMembershipClaimRepository.class);
        UUID purchaseId = UUID.randomUUID();
        UnlimitedMembershipClaim claim = new UnlimitedMembershipClaim("alice", purchaseId);
        when(repository.findById("alice")).thenReturn(Optional.of(claim));

        new UnlimitedClaimActivation(repository).activate("alice", purchaseId);

        assertThat(claim.getStatus()).isEqualTo(UnlimitedClaimStatus.ACTIVE);
    }

    @Test
    void rejectsActivationWhenClaimDoesNotExist() {
        UnlimitedMembershipClaimRepository repository = mock(UnlimitedMembershipClaimRepository.class);
        when(repository.findById("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new UnlimitedClaimActivation(repository).activate("alice", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        verify(repository).findById("alice");
    }
}
