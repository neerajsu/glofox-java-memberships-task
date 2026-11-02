package com.glofox.memberships.cache;

import static com.glofox.memberships.common.constant.Constants.API_JSON_CONTENT_TYPE;
import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_BY_ID_PATH;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.plan.constant.Constants.PLANS_CACHE;
import static com.glofox.memberships.plan.constant.Constants.PLANS_LIST_CACHE_KEY;
import static com.glofox.memberships.plan.constant.Constants.PLANS_PATH;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.entity.Plan;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalReadCacheTests {
    @Autowired MockMvc mvc;
    @Autowired CacheManager cacheManager;
    @Autowired PlanRepository plans;
    @Autowired PlanInventoryRepository inventory;
    @Autowired MembershipPurchaseRepository purchases;

    @BeforeEach
    void prepare() {
        clearCaches();
        purchases.deleteAll();
        restorePlan(SeedData.PLANS.get(0));
        inventory.findById(SeedData.LAUNCH_PROMO_PACK_ID).ifPresent(planInventory -> {
            planInventory.setAvailableSlots(5);
            inventory.saveAndFlush(planInventory);
        });
    }

    @AfterEach void clear() { clearCaches(); }

    @Test
    void repeatedPlanGetUsesLocalCache() throws Exception {
        mvc.perform(get(PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Monthly Unlimited")));
        org.assertj.core.api.Assertions.assertThat(cacheManager.getCache(PLANS_CACHE).get(PLANS_LIST_CACHE_KEY)).isNotNull();

        Plan current = plans.findById(SeedData.MONTHLY_UNLIMITED_ID).orElseThrow();
        plans.saveAndFlush(new Plan(current.getId(), "Changed behind cache", current.getType(),
                current.isActive(), current.getPriceCents(), current.getCredits()));

        mvc.perform(get(PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Monthly Unlimited")));
    }

    @Test
    void planListingReflectsInventoryChangesDespiteCachedPlanCatalog() throws Exception {
        var promoInventory = inventory.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        promoInventory.setAvailableSlots(1);
        inventory.saveAndFlush(promoInventory);
        clearCaches();

        mvc.perform(get(PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].remainingSlots", is(1)));
        org.assertj.core.api.Assertions.assertThat(cacheManager.getCache(PLANS_CACHE).get(PLANS_LIST_CACHE_KEY))
                .isNotNull();

        mvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(purchaseRequest(SeedData.LAUNCH_PROMO_PACK_ID)))
                .andExpect(status().isCreated());

        mvc.perform(get(PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[2].remainingSlots", is(0)));
        org.assertj.core.api.Assertions.assertThat(cacheManager.getCache(PLANS_CACHE).get(PLANS_LIST_CACHE_KEY))
                .isNotNull();
    }

    @Test
    void repeatedPurchaseGetsReadCommittedDatabaseChangesWithoutApplicationCaching() throws Exception {
        mvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(purchaseRequest(SeedData.TEN_CLASS_PACK_ID)))
                .andExpect(status().isCreated());
        var purchase = purchases.findAll().getFirst();
        mvc.perform(get(PURCHASE_BY_ID_PATH, purchase.getId()))
                .andExpect(jsonPath("$.memberName", is("Cache User")));
        mvc.perform(get(PURCHASE_COLLECTION_PATH))
                .andExpect(jsonPath("$.totalElements", is(1)));

        purchases.saveAndFlush(new com.glofox.memberships.membership.repository.entity.MembershipPurchase(
                purchase.getId(), "Updated display", purchase.getMemberKey(), purchase.getPlanId(), purchase.getPlanName(),
                purchase.getPlanType(), purchase.getPriceCents(), purchase.getCredits(), purchase.getStartDate(),
                purchase.getStatus(), purchase.getPaymentTransactionId(), purchase.getCreatedAt()));
        mvc.perform(get(PURCHASE_BY_ID_PATH, purchase.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberName", is("Updated display")));
        mvc.perform(get(PURCHASE_COLLECTION_PATH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].memberName", is("Updated display")));

        // A write that bypasses the POST/finalizer must also be visible without any cache eviction.
        purchases.saveAndFlush(new com.glofox.memberships.membership.repository.entity.MembershipPurchase(
                UUID.randomUUID(), "New purchase", "new purchase", purchase.getPlanId(), purchase.getPlanName(),
                purchase.getPlanType(), purchase.getPriceCents(), purchase.getCredits(), purchase.getStartDate(),
                purchase.getStatus(), "another-transaction", java.time.Instant.now()));
        mvc.perform(get(PURCHASE_COLLECTION_PATH))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].memberName", is("New purchase")));
        org.assertj.core.api.Assertions.assertThat(cacheManager.getCacheNames()).containsExactly(PLANS_CACHE);
    }

    private void restorePlan(SeedData.PlanSeed seed) {
        plans.saveAndFlush(new Plan(seed.id(), seed.name(), PlanType.valueOf(seed.type().toUpperCase()),
                seed.active(), seed.priceCents(), seed.credits()));
    }

    private String purchaseRequest(UUID planId) {
        return """
                {"memberName":"Cache User","planId":"%s","startDate":"2026-09-01"}
                """.formatted(planId);
    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }
}
