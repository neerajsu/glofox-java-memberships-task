package com.glofox.memberships.membership.service.claim;

import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UnlimitedClaimActivation {
    private final UnlimitedMembershipClaimRepository repository;
    @Transactional public void activate(String memberKey, UUID purchaseId) {
        repository.findById(memberKey).orElseThrow(() -> new IllegalStateException("Unlimited membership claim was not found"))
                .activate(purchaseId);
    }
}
