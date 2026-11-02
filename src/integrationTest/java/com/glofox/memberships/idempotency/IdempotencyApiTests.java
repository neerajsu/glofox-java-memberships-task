package com.glofox.memberships.idempotency;

import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.idempotency.constant.Constants.IN_PROGRESS_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REQUIRED_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REUSED_CODE;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_DECLINED_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.idempotency.repository.entity.IdempotencyRecord;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IdempotencyApiTests.PaymentConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyApiTests {

    private static final String PROMO_ID = "33333333-3333-3333-3333-333333333333";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MembershipPurchaseRepository purchaseRepository;

    @Autowired
    private PurchaseSagaRepository sagaRepository;

    @Autowired
    private UnlimitedMembershipClaimRepository claimRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRepository;

    @Autowired
    private PlanInventoryRepository inventoryRepository;

    @Autowired
    private RecordingPaymentGateway paymentGateway;

    @BeforeEach
    void resetStore() {
        purchaseRepository.deleteAll();
        claimRepository.deleteAll();
        reservationRepository.deleteAll();
        sagaRepository.deleteAll();
        idempotencyRepository.deleteAll();
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(5);
        inventoryRepository.saveAndFlush(inventory);
        paymentGateway.reset();
    }

    @Test
    void replaysSuccessfulPurchaseForSameSemanticRequestAndPersistsSeparateProviderKey() throws Exception {
        String key = "client-request-123";
        MvcResult first = mockMvc.perform(purchase(key, "Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01"))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = responseId(first);

        MvcResult replay = mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-09-01","planId":"22222222-2222-2222-2222-222222222222","memberName":" alice "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(firstId)))
                .andReturn();

        assertThat(responseId(replay)).isEqualTo(firstId);
        assertThat(paymentGateway.calls()).isEqualTo(1);
        IdempotencyRecord record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getPurchaseId()).isEqualTo(UUID.fromString(firstId));
        assertThat(record.getProviderIdempotencyKey()).isEqualTo(paymentGateway.lastCommand().idempotencyKey());
        assertThat(record.getProviderIdempotencyKey()).isNotEqualTo(key);
        assertThat(purchaseRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentSemanticPayload() throws Exception {
        mockMvc.perform(purchase("key-mismatch", "Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01"))
                .andExpect(status().isCreated());

        mockMvc.perform(purchase("key-mismatch", "Alice", SeedData.MONTHLY_UNLIMITED_ID.toString(), "2026-09-01"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(KEY_REUSED_CODE)));

        assertThat(paymentGateway.calls()).isEqualTo(1);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        assertThat(sagaRepository.count()).isEqualTo(1);
    }

    @Test
    void replaysDeterministicDeclineWithoutChargingAgain() throws Exception {
        paymentGateway.setOutcome(RecordingPaymentGateway.Outcome.DECLINED);

        mockMvc.perform(purchase("declined-request", "Alice", PROMO_ID, "2026-09-01"))
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.code", is(PAYMENT_DECLINED_CODE)));
        mockMvc.perform(purchase("declined-request", "Alice", PROMO_ID, "2026-09-01"))
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.code", is(PAYMENT_DECLINED_CODE)));

        assertThat(paymentGateway.calls()).isEqualTo(1);
        assertThat(purchaseRepository.count()).isZero();
        assertThat(inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow().getAvailableSlots())
                .isEqualTo(5);
        assertThat(idempotencyRepository.findById("declined-request").orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("Alice", SeedData.TEN_CLASS_PACK_ID.toString(), "2026-09-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is(KEY_REQUIRED_CODE)));

        assertThat(paymentGateway.calls()).isZero();
    }

    @Test
    void concurrentDuplicateWhileFirstRequestIsChargingReturnsInProgress() throws Exception {
        paymentGateway.pauseNextCharge();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mockMvc.perform(
                    purchase("concurrent-key", "Alice", PROMO_ID, "2026-09-01")).andReturn());
            assertThat(paymentGateway.awaitChargeStarted()).isTrue();

            mockMvc.perform(purchase("concurrent-key", "Alice", PROMO_ID, "2026-09-01"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code", is(IN_PROGRESS_CODE)));

            paymentGateway.continueCharge();
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            paymentGateway.continueCharge();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(paymentGateway.calls()).isEqualTo(1);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        assertThat(sagaRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyCreatesOnePurchaseAndOneCharge() throws Exception {
        paymentGateway.pauseNextCharge();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> submitAtStart(ready, start));
            Future<Integer> second = executor.submit(() -> submitAtStart(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(paymentGateway.awaitChargeStarted()).isTrue();
            paymentGateway.continueCharge();
            int firstStatus = first.get(10, TimeUnit.SECONDS);
            int secondStatus = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstStatus, secondStatus)).contains(201);
            assertThat(List.of(firstStatus, secondStatus)).allMatch(status -> status == 201 || status == 409);
        } finally {
            start.countDown();
            paymentGateway.continueCharge();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(paymentGateway.calls()).isEqualTo(1);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        assertThat(sagaRepository.count()).isEqualTo(1);
    }

    private int submitAtStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent test start latch timed out");
        }
        return mockMvc.perform(purchase("same-concurrent-key", "Alice", PROMO_ID, "2026-09-01"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder purchase(
            String key, String member, String plan, String date) {
        return post(PURCHASE_COLLECTION_PATH)
                .header(IDEMPOTENCY_KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(member, plan, date));
    }

    private String request(String memberName, String planId, String startDate) {
        return """
                {"memberName":"%s","planId":"%s","startDate":"%s"}
                """.formatted(memberName, planId, startDate);
    }

    private String responseId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Purchase response did not include an id");
        }
        return matcher.group(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PaymentConfiguration {

        @Bean
        @Primary
        RecordingPaymentGateway recordingPaymentGateway() {
            return new RecordingPaymentGateway();
        }
    }

    static class RecordingPaymentGateway implements PaymentGateway {

        private final AtomicInteger callCount = new AtomicInteger();
        private volatile Outcome outcome = Outcome.SUCCESS;
        private volatile PaymentCommand lastCommand;
        private volatile CountDownLatch chargeStarted = new CountDownLatch(0);
        private volatile CountDownLatch continueCharge = new CountDownLatch(0);

        @Override
        public PaymentResult charge(PaymentCommand command) {
            callCount.incrementAndGet();
            lastCommand = command;
            chargeStarted.countDown();
            try {
                if (!continueCharge.await(10, TimeUnit.SECONDS)) {
                    throw new PaymentProviderException(FailureType.UNKNOWN, "test charge timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PaymentProviderException(FailureType.UNKNOWN, "test charge interrupted");
            }
            return switch (outcome) {
                case SUCCESS -> new PaymentResult("txn-idempotency-test");
                case DECLINED -> throw new PaymentDeclinedException("declined");
            };
        }

        void reset() {
            callCount.set(0);
            outcome = Outcome.SUCCESS;
            lastCommand = null;
            chargeStarted = new CountDownLatch(0);
            continueCharge = new CountDownLatch(0);
        }

        void setOutcome(Outcome outcome) {
            this.outcome = outcome;
        }

        void pauseNextCharge() {
            chargeStarted = new CountDownLatch(1);
            continueCharge = new CountDownLatch(1);
        }

        boolean awaitChargeStarted() throws InterruptedException {
            return chargeStarted.await(5, TimeUnit.SECONDS);
        }

        void continueCharge() {
            continueCharge.countDown();
        }

        int calls() {
            return callCount.get();
        }

        PaymentCommand lastCommand() {
            return lastCommand;
        }

        enum Outcome {
            SUCCESS,
            DECLINED
        }
    }
}
