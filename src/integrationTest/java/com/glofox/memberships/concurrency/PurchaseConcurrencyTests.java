package com.glofox.memberships.concurrency;

import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.model.UnlimitedClaimStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlanInventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private MembershipPurchaseRepository purchaseRepository;

    @Autowired
    private UnlimitedMembershipClaimRepository claimRepository;

    @Autowired
    private InventoryReservationService reservationService;

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
    void concurrentRequestsCannotBuyTheLastPromoSlotTwice() throws Exception {
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(1);
        inventoryRepository.saveAndFlush(inventory);

        List<Integer> statuses = runTogether(
                purchaseRequest("First Member", SeedData.LAUNCH_PROMO_PACK_ID),
                purchaseRequest("Second Member", SeedData.LAUNCH_PROMO_PACK_ID));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        PlanInventory afterPurchase = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        assertThat(afterPurchase.getAvailableSlots()).isZero();
        assertThat(afterPurchase.getReservedSlots()).isZero();
        assertThat(reservationRepository.findAll())
                .singleElement()
                .extracting(InventoryReservation::getStatus)
                .isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    void concurrentNormalizedMemberRequestsCreateAtMostOneUnlimitedClaim() throws Exception {
        List<Integer> statuses = runTogether(
                purchaseRequest("  Alice  ", SeedData.MONTHLY_UNLIMITED_ID),
                purchaseRequest("alice", SeedData.MONTHLY_UNLIMITED_ID));

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        assertThat(claimRepository.findById("alice"))
                .get()
                .extracting(claim -> claim.getStatus())
                .isEqualTo(UnlimitedClaimStatus.ACTIVE);
    }

    @Test
    void repeatedReservationCommitAndReleasePreserveInventory() {
        UUID releasePurchaseId = UUID.randomUUID();
        reservationService.reserveOne(SeedData.LAUNCH_PROMO_PACK_ID, releasePurchaseId);
        reservationService.release(releasePurchaseId);
        reservationService.release(releasePurchaseId);

        PlanInventory afterRelease = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        assertThat(afterRelease.getAvailableSlots()).isEqualTo(5);
        assertThat(afterRelease.getReservedSlots()).isZero();
        assertThat(reservationRepository.findById(releasePurchaseId))
                .get()
                .extracting(InventoryReservation::getStatus)
                .isEqualTo(ReservationStatus.RELEASED);

        UUID commitPurchaseId = UUID.randomUUID();
        reservationService.reserveOne(SeedData.LAUNCH_PROMO_PACK_ID, commitPurchaseId);
        reservationService.commit(commitPurchaseId);
        reservationService.commit(commitPurchaseId);

        PlanInventory afterCommit = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        assertThat(afterCommit.getAvailableSlots()).isEqualTo(4);
        assertThat(afterCommit.getReservedSlots()).isZero();
        assertThat(reservationRepository.findById(commitPurchaseId))
                .get()
                .extracting(InventoryReservation::getStatus)
                .isEqualTo(ReservationStatus.COMMITTED);
    }

    private MockHttpServletRequestBuilder purchaseRequest(String memberName, UUID planId) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(PURCHASE_COLLECTION_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                .content("""
                        {"memberName":"%s","planId":"%s","startDate":"2026-09-01"}
                        """.formatted(memberName, planId));
    }

    private List<Integer> runTogether(
            MockHttpServletRequestBuilder firstRequest,
            MockHttpServletRequestBuilder secondRequest) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(sendWhenReleased(ready, start, firstRequest));
            Future<Integer> second = executor.submit(sendWhenReleased(ready, start, secondRequest));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Callable<Integer> sendWhenReleased(
            CountDownLatch ready,
            CountDownLatch start,
            MockHttpServletRequestBuilder request) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test start latch timed out");
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            return result.getResponse().getStatus();
        };
    }
}
