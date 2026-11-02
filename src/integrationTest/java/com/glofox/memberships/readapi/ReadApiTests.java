package com.glofox.memberships.readapi;

import static com.glofox.memberships.common.constant.Constants.INVALID_PARAMETER_CODE;
import static com.glofox.memberships.membership.constant.Constants.INVALID_LIMIT_CODE;
import static com.glofox.memberships.membership.constant.Constants.INVALID_PAGE_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_BY_ID_PATH;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_NOT_FOUND_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_LIMIT;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_DEFAULT_PAGE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_QUERY_MAX_LIMIT;
import static com.glofox.memberships.plan.constant.Constants.PLANS_PATH;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.membership.model.PurchaseStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import com.glofox.memberships.plan.repository.entity.Plan;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReadApiTests {

    private static final UUID FIRST_PURCHASE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SECOND_PURCHASE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID THIRD_PURCHASE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant PURCHASE_TIME = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MembershipPurchaseRepository purchaseRepository;

    @Autowired private PlanRepository planRepository;
    @Autowired private PlanInventoryRepository inventoryRepository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        var seed = SeedData.PLANS.get(0);
        planRepository.saveAndFlush(new Plan(seed.id(), seed.name(), PlanType.valueOf(seed.type().toUpperCase()),
                seed.active(), seed.priceCents(), seed.credits()));
        inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).ifPresent(inventory -> {
            inventory.setAvailableSlots(5);
            inventoryRepository.saveAndFlush(inventory);
        });
        purchaseRepository.deleteAll();
        purchaseRepository.saveAllAndFlush(List.of(
                purchase(FIRST_PURCHASE_ID),
                purchase(SECOND_PURCHASE_ID),
                purchase(THIRD_PURCHASE_ID)));
    }

    @Test
    void listsAllPlansIncludingInactiveWithCamelCaseFields() throws Exception {
        mockMvc.perform(get(PLANS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].name", is("Monthly Unlimited")))
                .andExpect(jsonPath("$[0].type", is("unlimited")))
                .andExpect(jsonPath("$[0].priceCents", is(8000)))
                .andExpect(jsonPath("$[2].remainingSlots", is(5)))
                .andExpect(jsonPath("$[3].name", is("Legacy Plan")))
                .andExpect(jsonPath("$[3].active", is(false)));
    }

    @Test
    void retrievesPurchaseSnapshot() throws Exception {
        mockMvc.perform(get(PURCHASE_BY_ID_PATH, SECOND_PURCHASE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(SECOND_PURCHASE_ID.toString())))
                .andExpect(jsonPath("$.memberName", is("Test Member")))
                .andExpect(jsonPath("$.planName", is("10-Class Pack")))
                .andExpect(jsonPath("$.planType", is("pack")))
                .andExpect(jsonPath("$.priceCents", is(5000)))
                .andExpect(jsonPath("$.startDate", is("2026-09-01")))
                .andExpect(jsonPath("$.paymentTransactionId", is("txn-test")));
    }

    @Test
    void returnsStableNotFoundErrorForUnknownPurchase() throws Exception {
        mockMvc.perform(get(PURCHASE_BY_ID_PATH, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(PURCHASE_NOT_FOUND_CODE)));
    }

    @Test
    void listsPurchasesWithDefaultPaginationAndDeterministicTieBreak() throws Exception {
        mockMvc.perform(get(PURCHASE_COLLECTION_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page", is(PURCHASE_QUERY_DEFAULT_PAGE)))
                .andExpect(jsonPath("$.limit", is(PURCHASE_QUERY_DEFAULT_LIMIT)))
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.hasMore", is(false)))
                .andExpect(jsonPath("$.content[0].id", is(THIRD_PURCHASE_ID.toString())));
    }

    @Test
    void rejectsInvalidPageAndLimitBounds() throws Exception {
        mockMvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_PAGE_CODE)));

        mockMvc.perform(get(PURCHASE_COLLECTION_PATH).param("limit", Integer.toString(PURCHASE_QUERY_MAX_LIMIT + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_LIMIT_CODE)));

        mockMvc.perform(get(PURCHASE_COLLECTION_PATH).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_LIMIT_CODE)));
    }

    @Test
    void rejectsMalformedPurchaseIdAndNonNumericPagination() throws Exception {
        mockMvc.perform(get("/v1/memberships/purchases/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_PARAMETER_CODE)));

        mockMvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_PARAMETER_CODE)));
    }

    private MembershipPurchase purchase(UUID id) {
        return new MembershipPurchase(
                id,
                "Test Member",
                "test member",
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "10-Class Pack",
                PlanType.PACK,
                5_000,
                10,
                LocalDate.parse("2026-09-01"),
                PurchaseStatus.ACTIVE,
                "txn-test",
                PURCHASE_TIME);
    }
}
