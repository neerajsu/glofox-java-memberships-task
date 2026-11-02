package com.glofox.memberships.membership.api;

import static com.glofox.memberships.common.constant.Constants.API_JSON_CONTENT_TYPE;
import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.membership.constant.Constants.COMPLETED_STATUS;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_DECLINED_CODE;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_UNAVAILABLE_CODE;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryService;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentFailureApiTests.GatewayConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentFailureApiTests {

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

    @Autowired
    private ScenarioPaymentGateway paymentGateway;

    @Autowired private PurchaseSagaRepository sagaRepository;
    @Autowired private PurchaseSagaStore sagaStore;
    @Autowired private PurchaseSagaRecoveryService recoveryService;
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;

    @BeforeEach
    void resetStore() {
        purchaseRepository.deleteAll();
        claimRepository.deleteAll();
        reservationRepository.deleteAll();
        PlanInventory inventory = inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow();
        inventory.setAvailableSlots(5);
        inventoryRepository.saveAndFlush(inventory);
        paymentGateway.setOutcome(ScenarioPaymentGateway.Outcome.DECLINED);
        paymentGateway.setTransactionActive(false);
        paymentGateway.resetCalls();
        sagaRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        idempotencyRepository.deleteAll();
    }

    @Test
    void declineReturns402AndReleasesPromoReservationWithoutPurchase() throws Exception {
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(request("Alice", SeedData.LAUNCH_PROMO_PACK_ID.toString())))
                .andExpect(status().is(402))
                .andExpect(jsonPath("$.code", is(PAYMENT_DECLINED_CODE)));

        assertNoPurchaseAndNoActiveReservation();
        org.assertj.core.api.Assertions.assertThat(
                inventoryRepository.findById(SeedData.LAUNCH_PROMO_PACK_ID).orElseThrow().getAvailableSlots())
                .isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(paymentGateway.isTransactionActive()).isFalse();
        org.assertj.core.api.Assertions.assertThat(idempotencyRepository.findById(key).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void transientProviderFailureReturns503AndReleasesUnlimitedClaim() throws Exception {
        paymentGateway.setOutcome(ScenarioPaymentGateway.Outcome.TRANSIENT_FAILURE);

        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(request("Alice", SeedData.MONTHLY_UNLIMITED_ID.toString())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is(PAYMENT_PROVIDER_UNAVAILABLE_CODE)));

        assertNoPurchaseAndNoActiveReservation();
        org.assertj.core.api.Assertions.assertThat(claimRepository.count()).isZero();
        org.assertj.core.api.Assertions.assertThat(paymentGateway.isTransactionActive()).isFalse();
    }

    @Test
    void unknownPaymentIsRecoveredWithSameAttemptAndCreatesOnePurchase() throws Exception {
        paymentGateway.setOutcome(ScenarioPaymentGateway.Outcome.UNKNOWN_ONCE);
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(request("Alice", SeedData.LAUNCH_PROMO_PACK_ID.toString())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is(PAYMENT_OUTCOME_UNKNOWN_CODE)));

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isZero();
        var saga = sagaRepository.findById(idempotencyRepository.findById(key).orElseThrow().getOperationId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saga.getStatus().name()).isEqualTo("PAYMENT_UNKNOWN");
        org.assertj.core.api.Assertions.assertThat(reservationRepository.findById(saga.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        sagaStore.deferRecovery(saga.getId(), java.time.Instant.now().minusSeconds(1));

        recoveryService.recoverPending(20);

        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(sagaRepository.findById(saga.getId()).orElseThrow().getStatus().name())
                .isEqualTo(COMPLETED_STATUS);
        org.assertj.core.api.Assertions.assertThat(paymentGateway.getCalls()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(reservationRepository.findById(saga.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMMITTED);
        org.assertj.core.api.Assertions.assertThat(idempotencyRepository.findById(key).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    private void assertNoPurchaseAndNoActiveReservation() {
        org.assertj.core.api.Assertions.assertThat(purchaseRepository.count()).isZero();
        org.assertj.core.api.Assertions.assertThat(reservationRepository.findAll())
                .allMatch(reservation -> reservation.getStatus() == ReservationStatus.RELEASED);
    }

    private String request(String memberName, String planId) {
        return """
                {"memberName":"%s","planId":"%s","startDate":"2026-09-01"}
                """.formatted(memberName, planId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {

        @Bean
        @Primary
        ScenarioPaymentGateway scenarioPaymentGateway() {
            return new ScenarioPaymentGateway();
        }
    }

    static class ScenarioPaymentGateway implements PaymentGateway {

        private volatile Outcome outcome = Outcome.DECLINED;
        private volatile boolean transactionActive;
        private volatile int calls;

        @Override
        public PaymentResult charge(PaymentCommand command) {
            calls++;
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            return switch (outcome) {
                case SUCCESS -> new PaymentResult("txn-test-success");
                case DECLINED -> throw new PaymentDeclinedException("declined");
                case TRANSIENT_FAILURE -> throw new PaymentProviderException(FailureType.TRANSIENT, "offline");
                case UNKNOWN_ONCE -> {
                    if (calls == 1) throw new PaymentProviderException(FailureType.UNKNOWN, "timeout");
                    yield new PaymentResult("txn-reconciled");
                }
            };
        }

        void setOutcome(Outcome outcome) {
            this.outcome = outcome;
        }

        boolean isTransactionActive() {
            return transactionActive;
        }

        int getCalls() { return calls; }
        void resetCalls() { calls = 0; }

        void setTransactionActive(boolean transactionActive) {
            this.transactionActive = transactionActive;
        }

        enum Outcome {
            SUCCESS,
            DECLINED,
            TRANSIENT_FAILURE
            , UNKNOWN_ONCE
        }
    }
}
