package com.glofox.memberships.membership.saga.recovery;

import static com.glofox.memberships.config.SeedData.LAUNCH_PROMO_PACK_ID;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.common.exception.OptimisticLockRetryExhaustedException;
import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.membership.saga.orchestration.payment.PurchasePaymentPreparation;
import com.glofox.memberships.membership.saga.orchestration.reservation.PurchaseResourceReservation;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseLookup;
import com.glofox.memberships.membership.service.purchase.PurchaseInitializationService;
import com.glofox.memberships.membership.service.purchase.PurchaseService;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PurchaseSagaRecoveryTests.GatewayConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseSagaRecoveryTests {
    @Autowired PurchaseSagaRecoveryService recovery;
    @Autowired MockMvc mvc;
    @Autowired PurchaseSagaStore sagas;
    @Autowired PurchaseInitializationService purchaseInitialization;
    @Autowired PlanPurchaseLookup planLookup;
    @Autowired PurchaseResourceReservation reservation;
    @Autowired PurchasePaymentPreparation paymentPreparation;
    @MockitoSpyBean PurchasePaymentOutcomeStore outcomes;
    @Autowired PurchaseService purchaseService;
    @MockitoSpyBean com.glofox.memberships.membership.service.purchase.PurchaseFinalizer finalizer;
    @MockitoSpyBean com.glofox.memberships.membership.saga.orchestration.compensation.PurchaseCompensationService compensation;
    @Autowired PaymentAttemptStore attempts;
    @Autowired PurchaseSagaRepository sagaRepository;
    @Autowired PaymentAttemptRepository attemptRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired MembershipPurchaseRepository purchases;
    @Autowired InventoryReservationRepository reservations;
    @Autowired UnlimitedMembershipClaimRepository claims;
    @Autowired PlanInventoryRepository planInventory;
    @Autowired IdempotentTestGateway gateway;

    @BeforeEach
    void reset() {
        purchases.deleteAll();
        reservations.deleteAll();
        claims.deleteAll();
        sagaRepository.deleteAll();
        attemptRepository.deleteAll();
        idempotencyRepository.deleteAll();
        planInventory.findById(LAUNCH_PROMO_PACK_ID).ifPresent(inventory -> {
            inventory.setAvailableSlots(5);
            planInventory.saveAndFlush(inventory);
        });
        gateway.reset();
    }

    @Test
    void releasesReservationWhenCrashHappensBeforePaymentAttemptIsPersisted() {
        var operation = createReservedOperation();
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        assertThat(recovery.recoverPending(10)).isEqualTo(1);

        assertThat(gateway.chargeCount()).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(planInventory.findById(LAUNCH_PROMO_PACK_ID).orElseThrow().getAvailableSlots()).isEqualTo(5);
        assertThat(reservations.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPENSATED);
        assertThat(idempotencyRepository.findById(operation.clientKey()).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    void retriesPaymentPendingWithSameKeyAfterCrashBeforeProviderInvocation() {
        var operation = createPendingOperation();
        PaymentAttemptStatus status = attempts.findByPurchaseAttemptId(operation.operationId()).getStatus();
        assertThat(status).isEqualTo(PaymentAttemptStatus.PROCESSING);
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        assertThat(recovery.recoverPending(10)).isEqualTo(1);

        assertThat(gateway.chargeCount()).isEqualTo(1);
        assertThat(gateway.seenProviderKeys()).hasSize(1);
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPLETED);
    }

    @Test
    void providerSuccessBeforeLocalOutcomePersistenceIsReconciledWithoutDuplicateCharge() {
        var operation = createPendingOperation();
        var attempt = attempts.findByPurchaseAttemptId(operation.operationId());
        String providerKey = attempt.getProviderIdempotencyKey();
        gateway.charge(new PaymentCommand(attempt.getId(), providerKey, attempt.getAmountCents(),
                attempt.getMemberName(), operation.operationId()));
        assertThat(gateway.chargeCount()).isEqualTo(1);
        assertThat(attempts.findByPurchaseAttemptId(operation.operationId()).getStatus())
                .isEqualTo(PaymentAttemptStatus.PROCESSING);
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        recovery.recoverPending(10);

        assertThat(gateway.chargeCount()).isEqualTo(1);
        assertThat(gateway.seenProviderKeys()).containsExactly(providerKey, providerKey);
        assertThat(attempts.findByPurchaseAttemptId(operation.operationId()).getStatus())
                .isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(purchases.count()).isEqualTo(1);
    }

    @Test
    void finalizesLocallyConfirmedPaymentWithoutChargingAgain() {
        var operation = createPendingOperation();
        var attempt = attempts.findByPurchaseAttemptId(operation.operationId());
        String providerKey = attempt.getProviderIdempotencyKey();
        String transactionId = gateway.charge(new PaymentCommand(attempt.getId(), providerKey,
                attempt.getAmountCents(), attempt.getMemberName(), operation.operationId())).transactionId();
        outcomes.succeeded(operation.operationId(), transactionId);
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        recovery.recoverPending(10);

        assertThat(gateway.chargeCount()).isEqualTo(1);
        assertThat(gateway.seenProviderKeys()).containsExactly(providerKey);
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPLETED);
    }

    @Test
    void recoveryFinalizationIsVisibleOnSubsequentPurchaseRead() throws Exception {
        var operation = createPendingOperation();
        var attempt = attempts.findByPurchaseAttemptId(operation.operationId());
        String transactionId = gateway.charge(new PaymentCommand(attempt.getId(),
                attempt.getProviderIdempotencyKey(), attempt.getAmountCents(), attempt.getMemberName(),
                operation.operationId())).transactionId();
        outcomes.succeeded(operation.operationId(), transactionId);
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        mvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "0").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));

        recovery.recoverPending(10);

        mvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "0").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));
        assertThat(idempotencyRepository.findById(operation.clientKey()).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void resumesCompensationAfterSagaTransitionWasPersisted() {
        var operation = createPendingOperation();
        outcomes.declined(operation.operationId(), "simulated decline before compensation");
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        recovery.recoverPending(10);

        assertThat(gateway.chargeCount()).isZero();
        assertThat(purchases.count()).isZero();
        assertThat(planInventory.findById(LAUNCH_PROMO_PACK_ID).orElseThrow().getAvailableSlots()).isEqualTo(5);
        assertThat(reservations.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPENSATED);
    }

    private IdempotencyService.StartResult createReservedOperation() {
        PurchaseRequest request = purchaseRequest();
        var operation = purchaseInitialization.initialize(UUID.randomUUID().toString(), request).operation();
        PlanPurchaseDetails plan = planLookup.findActivePlan(request.planId());
        String memberKey = request.memberName().trim().toLowerCase(java.util.Locale.ROOT);
        reservation.reserve(operation.operationId(), memberKey, plan);
        return operation;
    }

    @Test
    void outcomePersistenceExhaustionKeepsIdempotencyRecoverableAfterProviderSuccess() {
        String key = UUID.randomUUID().toString();
        var exhausted = new OptimisticLockRetryExhaustedException(
                new jakarta.persistence.OptimisticLockException("controlled conflict exhaustion"));
        org.mockito.Mockito.doThrow(exhausted).doCallRealMethod().when(outcomes)
                .succeeded(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> purchaseService.purchase(purchaseRequest(), key))
                .isSameAs(exhausted);
        var record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(sagaRepository.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.PAYMENT_PENDING);
        assertThat(gateway.chargeCount()).isEqualTo(1);

        sagas.deferRecovery(record.getOperationId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);

        assertThat(idempotencyRepository.findById(key).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(sagaRepository.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPLETED);
        assertThat(gateway.chargeCount()).isEqualTo(1);
    }

    @Test
    void providerShapedOutcomePersistenceFailureDoesNotCompensateAnAlreadyProcessedPayment() {
        String key = UUID.randomUUID().toString();
        var failure = new com.glofox.memberships.payment.exceptions.PaymentProviderException(
                FailureType.PERMANENT, "Injected outcome persistence failure after provider success");
        org.mockito.Mockito.doThrow(failure).doCallRealMethod().when(outcomes)
                .succeeded(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> purchaseService.purchase(purchaseRequest(), key))
                .isSameAs(failure);

        var record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(sagaRepository.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.PAYMENT_PENDING);
        assertThat(reservations.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(gateway.chargeCount()).isEqualTo(1);
        org.mockito.Mockito.verify(compensation, org.mockito.Mockito.never()).compensate(org.mockito.ArgumentMatchers.any());

        sagas.deferRecovery(record.getOperationId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);

        assertThat(purchases.count()).isEqualTo(1);
        assertThat(idempotencyRepository.findById(key).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(gateway.chargeCount()).isEqualTo(1);
    }

    @Test
    void providerShapedFinalizationFailureRetainsConfirmedPaymentAndRecoveryNeverCallsProviderAgain() {
        String key = UUID.randomUUID().toString();
        var failure = new com.glofox.memberships.payment.exceptions.PaymentProviderException(
                FailureType.PERMANENT, "Injected local finalization failure after provider success");
        org.mockito.Mockito.doThrow(failure).doCallRealMethod().when(finalizer).finalizePurchase(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> purchaseService.purchase(purchaseRequest(), key))
                .isSameAs(failure);

        var record = idempotencyRepository.findById(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(sagaRepository.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.PAYMENT_SUCCEEDED);
        assertThat(attempts.findByPurchaseAttemptId(record.getOperationId()).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(reservations.findById(record.getOperationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        org.mockito.Mockito.verify(compensation, org.mockito.Mockito.never()).compensate(org.mockito.ArgumentMatchers.any());

        sagas.deferRecovery(record.getOperationId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);

        assertThat(purchases.count()).isEqualTo(1);
        assertThat(idempotencyRepository.findById(key).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(gateway.chargeCount()).isEqualTo(1);
        assertThat(gateway.seenProviderKeys()).hasSize(1);
    }

    @Test
    void compensationExhaustionKeepsIdempotencyInProgressUntilRecoveryCompletes() {
        var operation = createPendingOperation();
        outcomes.declined(operation.operationId(), "declined");
        var exhausted = new OptimisticLockRetryExhaustedException(
                new jakarta.persistence.OptimisticLockException("controlled compensation exhaustion"));
        org.mockito.Mockito.doThrow(exhausted).doCallRealMethod().when(compensation)
                .compensate(org.mockito.ArgumentMatchers.any());
        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));

        recovery.recoverPending(10);

        assertThat(idempotencyRepository.findById(operation.clientKey()).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPENSATING);
        assertThat(reservations.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        sagas.deferRecovery(operation.operationId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);

        assertThat(idempotencyRepository.findById(operation.clientKey()).orElseThrow().getStatus())
                .isEqualTo(IdempotencyStatus.FAILED);
        assertThat(sagaRepository.findById(operation.operationId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseSagaStatus.COMPENSATED);
        assertThat(gateway.chargeCount()).isZero();
    }

    @Test
    void oneFailedSagaDoesNotPreventAnotherCandidateFromBeingRecovered() {
        var first = createPendingOperation();
        var second = createPendingOperation();
        outcomes.declined(first.operationId(), "declined");
        outcomes.declined(second.operationId(), "declined");
        var exhausted = new OptimisticLockRetryExhaustedException(
                new jakarta.persistence.OptimisticLockException("controlled conflict"));
        org.mockito.Mockito.doThrow(exhausted).doCallRealMethod().when(compensation)
                .compensate(org.mockito.ArgumentMatchers.any());
        sagas.deferRecovery(first.operationId(), Instant.now().minusSeconds(1));
        sagas.deferRecovery(second.operationId(), Instant.now().minusSeconds(1));

        assertThat(recovery.recoverPending(10)).isEqualTo(2);

        assertThat(sagaRepository.findAll()).extracting(saga -> saga.getStatus())
                .containsExactlyInAnyOrder(PurchaseSagaStatus.COMPENSATING, PurchaseSagaStatus.COMPENSATED);
    }

    private IdempotencyService.StartResult createPendingOperation() {
        var operation = createReservedOperation();
        paymentPreparation.prepare(operation.operationId(), operation.providerKey(), purchaseRequest().memberName(),
                2500, "recovery-test-correlation");
        attempts.startAttempt(operation.operationId());
        return operation;
    }

    private PurchaseRequest purchaseRequest() {
        return new PurchaseRequest("Recovery Test Member", LAUNCH_PROMO_PACK_ID, LocalDate.of(2026, 9, 1));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        @Primary
        IdempotentTestGateway idempotentTestGateway() {
            return new IdempotentTestGateway();
        }
    }

    static class IdempotentTestGateway implements PaymentGateway {
        private final Map<String, String> processed = new ConcurrentHashMap<>();
        private final List<String> seenProviderKeys = new CopyOnWriteArrayList<>();
        private final AtomicInteger chargeCount = new AtomicInteger();

        @Override
        public PaymentResult charge(PaymentCommand command) {
            seenProviderKeys.add(command.idempotencyKey());
            String transactionId = processed.computeIfAbsent(command.idempotencyKey(), key -> {
                chargeCount.incrementAndGet();
                return "txn-recovery-" + UUID.randomUUID();
            });
            return new PaymentResult(transactionId);
        }

        int chargeCount() { return chargeCount.get(); }
        List<String> seenProviderKeys() { return List.copyOf(seenProviderKeys); }
        void reset() { processed.clear(); seenProviderKeys.clear(); chargeCount.set(0); }
    }
}
