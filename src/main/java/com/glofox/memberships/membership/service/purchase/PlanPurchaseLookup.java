package com.glofox.memberships.membership.service.purchase;

import static com.glofox.memberships.plan.constant.Constants.PLAN_INACTIVE_CODE;
import static com.glofox.memberships.plan.constant.Constants.PLAN_NOT_FOUND_CODE;

import com.glofox.memberships.plan.exceptions.PlanException;
import com.glofox.memberships.plan.repository.entity.Plan;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanPurchaseLookup {

    private final PlanRepository planRepository;
    private final PlanInventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public PlanPurchaseDetails findActivePlan(UUID planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanException(HttpStatus.NOT_FOUND, PLAN_NOT_FOUND_CODE, "Plan was not found"));
        if (!plan.isActive()) {
            throw new PlanException(HttpStatus.CONFLICT, PLAN_INACTIVE_CODE, "The selected plan is inactive");
        }
        return new PlanPurchaseDetails(
                plan.getId(),
                plan.getName(),
                plan.getType(),
                plan.getPriceCents(),
                plan.getCredits(),
                inventoryRepository.existsById(planId));
    }
}
