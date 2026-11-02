package com.glofox.memberships.membership.service.claim;

import static com.glofox.memberships.membership.constant.Constants.MEMBERSHIP_ACTIVATION_FAILED_CODE;
import static com.glofox.memberships.testharness.constant.Constants.MEMBERSHIP_ACTIVATE_OPERATION;
import static com.glofox.memberships.testharness.constant.Constants.MEMBERSHIP_FAILURE_COUNT_SETTING;

import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import com.glofox.memberships.membership.repository.entity.UnlimitedMembershipClaim;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.testharness.annotation.FaultInjectable;
import com.glofox.memberships.testharness.model.FaultScenario;
import com.glofox.memberships.testharness.service.FaultInvocationContext;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UnlimitedMembershipClaimService {

    private final UnlimitedMembershipClaimRepository claimRepository;
    private final ObjectProvider<TestHarnessSettingsService> harnessSettings;
    private final UnlimitedClaimActivation activation;

    @Transactional
    public void claim(String memberKey, UUID purchaseId) {
        claimRepository.saveAndFlush(new UnlimitedMembershipClaim(memberKey, purchaseId));
    }

    @Transactional
    @FaultInjectable(value = MEMBERSHIP_ACTIVATE_OPERATION, simulationMethod = "simulateActivate")
    public void activate(String memberKey, UUID purchaseId) {
        activation.activate(memberKey, purchaseId);
    }

    public void simulateActivate(String memberKey, UUID purchaseId) {
        TestHarnessSettingsService settings = harnessSettings.getObject();
        if (FaultInvocationContext.current() == FaultScenario.MEMBERSHIP_ACTIVATION_ERROR
                && (!settings.counterConfigured(MEMBERSHIP_FAILURE_COUNT_SETTING)
                    || settings.consumeCounter(MEMBERSHIP_FAILURE_COUNT_SETTING))) {
            throw new MembershipException(HttpStatus.INTERNAL_SERVER_ERROR, MEMBERSHIP_ACTIVATION_FAILED_CODE,
                    "Simulated membership activation failure");
        }
        activation.activate(memberKey, purchaseId);
    }

    @Transactional
    public void releasePending(String memberKey, UUID purchaseId) {
        claimRepository.deleteByMemberKeyAndPurchaseIdAndStatus(
                memberKey,
                purchaseId,
                UnlimitedClaimStatus.PENDING);
    }
}
