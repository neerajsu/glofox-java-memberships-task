package com.glofox.memberships.membership.saga.orchestration.payment;

import static com.glofox.memberships.config.SeedData.LAUNCH_PROMO_PACK_ID;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_RETRY_INTERRUPTED_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaContext;
import com.glofox.memberships.membership.saga.orchestration.reservation.PurchaseResourceReservation;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryService;
import com.glofox.memberships.membership.service.purchase.PurchaseInitializationService;
import com.glofox.memberships.membership.service.purchase.PurchaseService;
import com.glofox.memberships.payment.AlwaysSucceedingPaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchasePaymentProcessorRecoveryTests {
    @Autowired PurchasePaymentProcessor processor;
    @Autowired PurchaseInitializationService initialization;
    @Autowired PurchaseResourceReservation reservation;
    @Autowired PurchasePaymentPreparation preparation;
    @Autowired PurchaseSagaStore sagas;
    @Autowired PurchaseSagaRecoveryService recovery;
    @Autowired PurchaseService purchases;
    @Autowired MembershipPurchaseRepository purchaseRepository;
    @Autowired IdempotencyRecordRepository records;
    @Autowired InventoryReservationRepository reservations;
    @Autowired PlanInventoryRepository inventory;
    @Autowired SimulatedPaymentChargeRepository charges;
    @MockitoSpyBean PaymentAttemptStore attempts;
    @MockitoSpyBean AlwaysSucceedingPaymentGateway gateway;

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void interruptedBackoffRetainsPendingStateAndRecoveryCompletesUsingSamePaymentWithoutDuplicateCharge() {
        String key = UUID.randomUUID().toString();
        var request = new PurchaseRequest("Interrupted Payment Member", LAUNCH_PROMO_PACK_ID, LocalDate.of(2026, 11, 2));
        var initialized = initialization.initialize(key, request);
        var operation = initialized.operation();
        var context = new PurchaseSagaContext(operation, request, initialized.plan(),
                request.memberName().toLowerCase(Locale.ROOT), "interrupted-backoff-test");
        UUID id = operation.operationId();
        reservation.reserve(id, context.memberKey(), context.plan());
        preparation.prepare(id, operation.providerKey(), request.memberName(), context.plan().priceCents(), context.correlationId());
        var attempt = attempts.findByPurchaseAttemptId(id);
        var command = new PaymentCommand(attempt.getId(), operation.providerKey(), attempt.getAmountCents(), request.memberName(), id);
        doThrow(new PaymentProviderException(FailureType.TRANSIENT, "temporary provider failure"))
                .doCallRealMethod().when(gateway).charge(any());
        doAnswer(invocation -> {
            // Interrupt after the retryable failure has really committed, immediately before backoff.
            invocation.callRealMethod();
            Thread.currentThread().interrupt();
            return null;
        }).when(attempts).recordRetryableFailure(eq(id), eq(FailureType.TRANSIENT), anyString());

        try {
            assertThatThrownBy(() -> processor.charge(context, command))
                    .isInstanceOfSatisfying(PaymentException.class, failure -> {
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(failure.getCode()).isEqualTo(PAYMENT_RETRY_INTERRUPTED_CODE);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(records.findById(key).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
        assertThat(sagas.findById(id).getStatus()).isEqualTo(PurchaseSagaStatus.PAYMENT_PENDING);
        var interruptedAttempt = attempts.findByPurchaseAttemptId(id);
        assertThat(interruptedAttempt.getStatus()).isEqualTo(PaymentAttemptStatus.PROCESSING);
        assertThat(interruptedAttempt.getLastFailureType()).isEqualTo(FailureType.TRANSIENT);
        assertThat(interruptedAttempt.getAttemptCount()).isEqualTo(1);
        assertThat(reservations.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(inventory.findById(LAUNCH_PROMO_PACK_ID).orElseThrow().getAvailableSlots()).isEqualTo(4);
        assertThat(purchaseRepository.count()).isZero();
        assertThat(charges.count()).isZero();
        verify(gateway, times(1)).charge(command);

        sagas.deferRecovery(id, Instant.now().minusSeconds(1));
        assertThat(recovery.recoverPending(10)).isEqualTo(1);

        assertThat(sagas.findById(id).getStatus()).isEqualTo(PurchaseSagaStatus.COMPLETED);
        assertThat(records.findById(key).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(reservations.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        assertThat(attempts.findByPurchaseAttemptId(id).getId()).isEqualTo(attempt.getId());
        assertThat(attempts.findByPurchaseAttemptId(id).getProviderIdempotencyKey()).isEqualTo(operation.providerKey());
        assertThat(attempts.findByPurchaseAttemptId(id).getAttemptCount()).isEqualTo(2);
        assertThat(purchaseRepository.count()).isEqualTo(1);
        assertThat(charges.count()).isEqualTo(1);
        assertThat(purchases.purchase(request, key).id()).isEqualTo(id);
        verify(gateway, times(2)).charge(command);
        assertThat(charges.count()).isEqualTo(1);
    }
}
