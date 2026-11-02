package com.glofox.memberships.plan.service.seeding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import com.glofox.memberships.plan.repository.entity.Plan;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlanSeederTests {

    private final PlanRepository plans = org.mockito.Mockito.mock(PlanRepository.class);
    private final PlanInventoryRepository inventory = org.mockito.Mockito.mock(PlanInventoryRepository.class);
    private final PlanSeeder seeder = new PlanSeeder(plans, inventory);

    @Test
    void insertsMissingPlansAndFiniteInventoryOnly() {
        when(plans.existsById(any())).thenAnswer(invocation ->
                SeedData.LEGACY_PLAN_ID.equals(invocation.getArgument(0)));
        when(plans.save(any(Plan.class))).thenAnswer(invocation -> invocation.getArgument(0));

        seeder.seed();

        ArgumentCaptor<Plan> savedPlans = ArgumentCaptor.forClass(Plan.class);
        verify(plans, org.mockito.Mockito.times(3)).save(savedPlans.capture());
        assertThat(savedPlans.getAllValues()).extracting(Plan::getName)
                .contains("Monthly Unlimited", "10-Class Pack", "Launch Promo Pack");
        verify(inventory, org.mockito.Mockito.times(1)).save(any(PlanInventory.class));
    }

    @Test
    void doesNotWriteAnythingWhenAllSeedPlansAlreadyExist() {
        when(plans.existsById(any())).thenReturn(true);

        seeder.seed();

        verify(plans, never()).save(any(Plan.class));
        verify(inventory, never()).save(any(PlanInventory.class));
    }
}
