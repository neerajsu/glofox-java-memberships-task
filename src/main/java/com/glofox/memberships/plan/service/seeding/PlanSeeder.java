package com.glofox.memberships.plan.service.seeding;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.entity.Plan;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanSeeder {

    private final PlanRepository planRepository;
    private final PlanInventoryRepository inventoryRepository;

    @Transactional
    public void seed() {
        for (SeedData.PlanSeed seed : SeedData.PLANS) {
            if (planRepository.existsById(seed.id())) {
                continue;
            }

            Plan plan = new Plan(
                    seed.id(),
                    seed.name(),
                    PlanType.valueOf(seed.type().toUpperCase(Locale.ROOT)),
                    seed.active(),
                    seed.priceCents(),
                    seed.credits());
            Plan savedPlan = planRepository.save(plan);

            if (seed.remainingSlots() != null) {
                inventoryRepository.save(new PlanInventory(savedPlan, seed.remainingSlots()));
            }
        }
    }
}
