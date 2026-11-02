package com.glofox.memberships.membership.api;

import static com.glofox.memberships.common.constant.Constants.API_JSON_CONTENT_TYPE;
import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.common.constant.Constants.INVALID_REQUEST_CODE;
import static com.glofox.memberships.inventory.constant.Constants.INVENTORY_EXHAUSTED_CODE;
import static com.glofox.memberships.membership.constant.Constants.ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.plan.constant.Constants.PLAN_INACTIVE_CODE;
import static com.glofox.memberships.plan.constant.Constants.PLAN_NOT_FOUND_CODE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MembershipPurchaseRepository purchaseRepository;

    @Autowired
    private UnlimitedMembershipClaimRepository claimRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private PlanInventoryRepository inventoryRepository;

    @BeforeEach
    void resetStore() {
        purchaseRepository.deleteAll();
        claimRepository.deleteAll();
        reservationRepository.deleteAll();
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(5);
        inventoryRepository.saveAndFlush(inventory);
    }

    @Test
    void purchasesPlanAndReturnsCreatedLocationAndSnapshot() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/v1/memberships/purchases/")))
                .andExpect(jsonPath("$.memberName", is("Alice")))
                .andExpect(jsonPath("$.planName", is("10-Class Pack")))
                .andExpect(jsonPath("$.priceCents", is(5000)))
                .andExpect(jsonPath("$.startDate", is("2026-09-01")))
                .andExpect(jsonPath("$.status", is("active")));

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isEqualTo(1);
    }

    @Test
    void creationGetAndIdempotentReplayReturnIdenticalSnapshotIncludingTimestamp() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = request("Timestamp Member", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01");
        var created = mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        mockMvc.perform(get(created.getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(content().json(created.getContentAsString()));
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().json(created.getContentAsString()));
        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsBlankNameAndMalformedDate() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("  ", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_REQUEST_CODE)));

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "not-a-date")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(INVALID_REQUEST_CODE)));
    }

    @Test
    void rejectsUnknownAndInactivePlans() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", "55555555-5555-5555-5555-555555555555", "2026-09-01")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(PLAN_NOT_FOUND_CODE)));

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.LEGACY_PLAN_ID.toString(), "2026-09-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(PLAN_INACTIVE_CODE)));

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isZero();
    }

    @Test
    void allowsOnlyOneUnlimitedPurchaseForNormalizedMemberButAllowsPacks() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request(" Alice ", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-08-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberName", is(" Alice ")));

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.MONTHLY_UNLIMITED_ID.toString(), "2026-09-01")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("  aLiCe  ", SeedData.MONTHLY_UNLIMITED_ID.toString(), "2026-10-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE)));

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-10-01")))
                .andExpect(status().isCreated());

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isEqualTo(3);
    }

    @Test
    void successfulPromoPurchaseConsumesOneAvailableSlot() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.LAUNCH_PROMO_PACK_ID.toString(), "2026-09-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priceCents", is(2500)))
                .andExpect(jsonPath("$.credits", is(5)));

        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(inventory.getAvailableSlots()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(inventory.getReservedSlots()).isZero();
    }

    @Test
    void rejectsPromoPurchaseWhenNoInventoryRemains() throws Exception {
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(0);
        inventoryRepository.saveAndFlush(inventory);

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.LAUNCH_PROMO_PACK_ID.toString(), "2026-09-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(INVENTORY_EXHAUSTED_CODE)));

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isZero();
    }

    private String request(String memberName, String planId, String startDate) {
        return """
                {"memberName":"%s","planId":"%s","startDate":"%s"}
                """.formatted(memberName, planId, startDate);
    }
}
