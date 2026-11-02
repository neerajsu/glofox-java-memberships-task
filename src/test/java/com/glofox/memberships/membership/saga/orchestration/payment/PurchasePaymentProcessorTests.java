package com.glofox.memberships.membership.saga.orchestration.payment;

import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_ATTEMPT_RETRY;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_ATTEMPT_STARTED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_SUCCEEDED;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_PAYMENT_MAX_ATTEMPTS;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_ERROR_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_UNAVAILABLE_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_RETRY_INTERRUPTED_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.orchestration.compensation.PurchaseCompensationService;
import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaContext;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import com.glofox.memberships.plan.model.PlanType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

class PurchasePaymentProcessorTests {
    private final PurchaseCompensationService compensation = mock(PurchaseCompensationService.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final PaymentAttemptStore attempts = mock(PaymentAttemptStore.class);
    private final PurchasePaymentOutcomeStore outcomes = mock(PurchasePaymentOutcomeStore.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PurchasePaymentFailureHandler failureHandler = new PurchasePaymentFailureHandler(compensation, sagas, outcomes);
    private final PurchasePaymentProcessor processor = new PurchasePaymentProcessor(attempts, outcomes, gateway, failureHandler);
    private final UUID id = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final PurchaseSaga saga = mock(PurchaseSaga.class);
    private final PaymentCommand command = new PaymentCommand(attemptId, "provider-key", 5000, "Alice", id);
    private final PurchaseSagaContext context = new PurchaseSagaContext(new StartResult("client-key", id, "provider-key", null, null),
            new PurchaseRequest("Alice", UUID.randomUUID(), LocalDate.of(2026, 11, 2)),
            new PlanPurchaseDetails(UUID.randomUUID(), "Pack", PlanType.PACK, 5000, 10, false), "alice", "cid");

    @BeforeEach
    void prepare() {
        var attempt = new PaymentAttempt(attemptId, id, command.idempotencyKey(), command.memberName(), command.amountCents(), context.correlationId());
        when(attempts.startAttempt(id)).thenAnswer(invocation -> {
            attempt.startAttempt();
            return attempt;
        });
        when(sagas.findById(id)).thenReturn(saga);
    }

    @Test
    void confirmsSuccessfulOutcomeBeforeReturningTransaction() {
        when(gateway.charge(command)).thenReturn(new PaymentResult("txn"));
        assertThat(processor.charge(context, command)).isEqualTo("txn");
        var order = inOrder(attempts, gateway, outcomes);
        order.verify(attempts).startAttempt(id);
        order.verify(gateway).charge(command);
        order.verify(outcomes).succeeded(id, "txn");
        verify(gateway, times(1)).charge(command);
        verifyNoInteractions(compensation);
    }

    @Test
    void oneTransientFailureThenSuccessStartsBothAttemptsAndRecordsFailureBeforeRetry() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT))
                .thenReturn(new PaymentResult("txn"));

        assertThat(processor.charge(context, command)).isEqualTo("txn");

        var order = inOrder(attempts, gateway, outcomes);
        order.verify(attempts).startAttempt(id);
        order.verify(gateway).charge(command);
        order.verify(attempts).recordRetryableFailure(eq(id), eq(FailureType.TRANSIENT), anyString());
        order.verify(attempts).startAttempt(id);
        order.verify(gateway).charge(command);
        order.verify(outcomes).succeeded(id, "txn");
        order.verifyNoMoreInteractions();
        verifyNoInteractions(compensation);
    }

    @Test
    void declinePersistsThenCompensatesWithExisting402Mapping() {
        when(gateway.charge(command)).thenThrow(new PaymentDeclinedException("declined"));
        assertThatThrownBy(() -> processor.charge(context, command)).isInstanceOfSatisfying(PaymentDeclinedException.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
        var order = inOrder(outcomes, compensation);
        order.verify(outcomes).declined(id, "The payment was declined");
        order.verify(compensation).compensate(saga);
        verify(gateway, times(1)).charge(command);
    }

    @Test
    void transientRetriesReuseCommandAndSucceedWithinThreeAttempts() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT))
                .thenThrow(providerFailure(FailureType.TRANSIENT)).thenReturn(new PaymentResult("txn"));
        assertThat(processor.charge(context, command)).isEqualTo("txn");
        verify(gateway, times(PURCHASE_PAYMENT_MAX_ATTEMPTS)).charge(command);
        verify(attempts, times(PURCHASE_PAYMENT_MAX_ATTEMPTS)).startAttempt(id);
        verify(attempts, times(2)).recordRetryableFailure(eq(id), eq(FailureType.TRANSIENT), anyString());
        verify(outcomes).succeeded(id, "txn");
        var order = inOrder(attempts, gateway, outcomes);
        for (int attempt = 1; attempt <= PURCHASE_PAYMENT_MAX_ATTEMPTS; attempt++) {
            order.verify(attempts).startAttempt(id);
            order.verify(gateway).charge(command);
            if (attempt < PURCHASE_PAYMENT_MAX_ATTEMPTS) order.verify(attempts).recordRetryableFailure(eq(id), eq(FailureType.TRANSIENT), anyString());
        }
        order.verify(outcomes).succeeded(id, "txn");
        order.verifyNoMoreInteractions();
        verifyNoInteractions(compensation);
    }

    @Test
    void transientExhaustionPersistsFailureAndCompensates() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT));
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, PAYMENT_PROVIDER_UNAVAILABLE_CODE);
        verify(gateway, times(PURCHASE_PAYMENT_MAX_ATTEMPTS)).charge(command);
        verify(outcomes).failed(eq(id), eq(FailureType.TRANSIENT), eq(HttpStatus.SERVICE_UNAVAILABLE),
                eq(PAYMENT_PROVIDER_UNAVAILABLE_CODE), anyString());
        verify(compensation).compensate(saga);
    }

    @Test
    void permanentFailureCompensatesWithoutRetry() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.PERMANENT));
        assertFailure(HttpStatus.BAD_GATEWAY, PAYMENT_PROVIDER_ERROR_CODE);
        verify(gateway, times(1)).charge(command);
        var order = inOrder(outcomes, compensation);
        order.verify(outcomes).failed(eq(id), eq(FailureType.PERMANENT), eq(HttpStatus.BAD_GATEWAY),
                eq(PAYMENT_PROVIDER_ERROR_CODE), anyString());
        order.verify(compensation).compensate(saga);
    }

    @Test
    void unknownPersistsReconciliationTimeWithoutRetryOrCompensation() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.UNKNOWN));
        Instant earliestReconciliation = Instant.now().plusSeconds(30);
        assertFailure(HttpStatus.SERVICE_UNAVAILABLE, PAYMENT_OUTCOME_UNKNOWN_CODE);
        var reconciliationTime = ArgumentCaptor.forClass(Instant.class);
        verify(outcomes).unknown(eq(id), reconciliationTime.capture(), anyString());
        assertThat(reconciliationTime.getValue()).isBetween(earliestReconciliation, Instant.now().plusSeconds(30));
        verify(gateway, times(1)).charge(command);
        verify(outcomes, never()).failed(any(), any(), any(), any(), any());
        verifyNoInteractions(compensation);
    }

    @Test
    void providerShapedExceptionFromSuccessPersistenceNeverTriggersCompensationOrAnotherCharge() {
        when(gateway.charge(command)).thenReturn(new PaymentResult("txn"));
        var persistenceFailure = providerFailure(FailureType.PERMANENT);
        doThrow(persistenceFailure).when(outcomes).succeeded(id, "txn");
        assertThatThrownBy(() -> processor.charge(context, command)).isSameAs(persistenceFailure);
        verify(gateway, times(1)).charge(command);
        verify(outcomes, never()).failed(any(), any(), any(), any(), any());
        verify(outcomes, never()).declined(any(), any());
        verifyNoInteractions(compensation);
    }

    @Test
    void arbitraryProviderExceptionIsNotRetriedOrTreatedAsDefinitiveDecline() {
        var failure = new IllegalStateException("unclassified provider failure");
        when(gateway.charge(command)).thenThrow(failure);
        assertThatThrownBy(() -> processor.charge(context, command)).isSameAs(failure);
        verify(gateway, times(1)).charge(command);
        verifyNoInteractions(outcomes, compensation);
    }

    @Test
    void attemptStartPersistenceFailureNeverInvokesGatewayOrClassifiesProviderFailure() {
        var failure = providerFailure(FailureType.PERMANENT);
        when(attempts.startAttempt(id)).thenThrow(failure);
        assertThatThrownBy(() -> processor.charge(context, command)).isSameAs(failure);
        verify(attempts, times(1)).startAttempt(id);
        verifyNoInteractions(gateway, outcomes, compensation);
    }

    @Test
    void retryPersistenceFailureDoesNotCauseAnotherChargeOrCompensation() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT));
        var failure = providerFailure(FailureType.PERMANENT);
        doThrow(failure).when(attempts).recordRetryableFailure(eq(id), eq(FailureType.TRANSIENT), anyString());
        assertThatThrownBy(() -> processor.charge(context, command)).isSameAs(failure);
        verify(gateway, times(1)).charge(command);
        verifyNoInteractions(outcomes, compensation);
    }

    @Test
    void logsEachAttemptWithCorrelationIdentifiersAndReusesExactCommand() {
        Logger logger = (Logger) LoggerFactory.getLogger(PurchasePaymentProcessor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT))
                .thenReturn(new PaymentResult("txn"));
        try {
            processor.charge(context, command);

            var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().filter(message -> message.contains("event=" + PAYMENT_ATTEMPT_STARTED)))
                    .hasSize(2).allSatisfy(message -> assertThat(message).contains(
                            "correlationId=" + context.correlationId(), "operationId=" + id, "paymentAttemptId=" + attemptId));
            assertThat(messages).anySatisfy(message -> assertThat(message).contains("event=" + PAYMENT_ATTEMPT_STARTED, "attemptNumber=1"));
            assertThat(messages).anySatisfy(message -> assertThat(message).contains("event=" + PAYMENT_ATTEMPT_STARTED, "attemptNumber=2"));
            assertThat(messages).anySatisfy(message -> assertThat(message).contains("event=" + PAYMENT_ATTEMPT_RETRY,
                    "correlationId=" + context.correlationId(), "operationId=" + id, "paymentAttemptId=" + attemptId,
                    "nextAttempt=2", "failureType=" + FailureType.TRANSIENT));
            assertThat(messages).anySatisfy(message -> assertThat(message).contains("event=" + PAYMENT_SUCCEEDED,
                    "correlationId=" + context.correlationId(), "operationId=" + id, "paymentAttemptId=" + attemptId));
            var commands = ArgumentCaptor.forClass(PaymentCommand.class);
            verify(gateway, times(2)).charge(commands.capture());
            assertThat(commands.getAllValues()).allSatisfy(value -> assertThat(value).isSameAs(command));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void interruptedBackoffPreservesInterruptAndLeavesWorkRecoverable() {
        when(gateway.charge(command)).thenThrow(providerFailure(FailureType.TRANSIENT));
        Thread.currentThread().interrupt();
        try {
            assertFailure(HttpStatus.SERVICE_UNAVAILABLE, PAYMENT_RETRY_INTERRUPTED_CODE);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(gateway, times(1)).charge(command);
            verifyNoInteractions(outcomes, compensation);
        } finally {
            Thread.interrupted();
        }
    }

    private PaymentProviderException providerFailure(FailureType type) {
        return new PaymentProviderException(type, "simulated provider failure");
    }

    private void assertFailure(HttpStatus status, String code) {
        assertThatThrownBy(() -> processor.charge(context, command)).isInstanceOfSatisfying(PaymentException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(status);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }
}
