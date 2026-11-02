package com.glofox.memberships.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import com.glofox.memberships.plan.service.seeding.PlanSeeder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlanSeedingTests {

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PlanInventoryRepository inventoryRepository;

    @Autowired
    private PlanSeeder planSeeder;

    @BeforeEach
    void restoreSeedPlanFixture() {
        var seed = SeedData.PLANS.get(0);
        planRepository.saveAndFlush(new com.glofox.memberships.plan.repository.entity.Plan(
                seed.id(), seed.name(), PlanType.valueOf(seed.type().toUpperCase()), seed.active(),
                seed.priceCents(), seed.credits()));
        inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).ifPresent(inventory -> {
            inventory.setAvailableSlots(5);
            inventoryRepository.saveAndFlush(inventory);
        });
    }

    @Test
    void persistsAllSeedPlansAndOnlyFiniteInventory() {
        assertThat(planRepository.findAll()).hasSize(4);

        var monthly = planRepository.findById(SeedData.MONTHLY_UNLIMITED_ID).orElseThrow();
        assertThat(monthly.getName()).isEqualTo("Monthly Unlimited");
        assertThat(monthly.getType()).isEqualTo(PlanType.UNLIMITED);
        assertThat(monthly.isActive()).isTrue();
        assertThat(monthly.getPriceCents()).isEqualTo(8_000);
        assertThat(monthly.getCredits()).isNull();

        var pack = planRepository.findById(SeedData.TEN_CLASS_PACK_ID).orElseThrow();
        assertThat(pack.getName()).isEqualTo("10-Class Pack");
        assertThat(pack.getCredits()).isEqualTo(10);

        var promo = planRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        assertThat(promo.getName()).isEqualTo("Launch Promo Pack");
        assertThat(promo.getPriceCents()).isEqualTo(2_500);
        assertThat(promo.getCredits()).isEqualTo(5);
        assertThat(inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID))
                .get()
                .extracting(PlanInventory::getAvailableSlots)
                .isEqualTo(5);

        var legacy = planRepository.findById(SeedData.LEGACY_PLAN_ID).orElseThrow();
        assertThat(legacy.getName()).isEqualTo("Legacy Plan");
        assertThat(legacy.isActive()).isFalse();
        assertThat(legacy.getPriceCents()).isEqualTo(6_000);
        assertThat(inventoryRepository.findAll()).hasSize(1);
    }

    @Test
    @DirtiesContext
    void reseedingDoesNotResetExistingInventory() {
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(2);
        inventoryRepository.saveAndFlush(inventory);

        planSeeder.seed();

        assertThat(inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID))
                .get()
                .extracting(PlanInventory::getAvailableSlots)
                .isEqualTo(2);
        assertThat(planRepository.findAll()).hasSize(4);
    }
}
