package com.glofox.memberships.membership.saga.orchestration.payment;

import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_ATTEMPT_RETRY;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_ATTEMPT_STARTED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_SUCCEEDED;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_PAYMENT_MAX_ATTEMPTS;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_RETRY_INTERRUPTED_CODE;

import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaContext;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Provider retry/classification is separate from local optimistic-lock retries and recovery. */
@Service
@Slf4j
@RequiredArgsConstructor
public class PurchasePaymentProcessor {
    private final PaymentAttemptStore paymentAttemptStore;
    private final PurchasePaymentOutcomeStore outcomeStore;
    private final PaymentGateway paymentGateway;
    private final PurchasePaymentFailureHandler failureHandler;

    public String charge(PurchaseSagaContext context, PaymentCommand command) {
        PaymentResult result = executePaymentWithRetry(context, command);
        UUID id = context.operation().operationId();
        outcomeStore.succeeded(id, result.transactionId());
        log.info("event={} correlationId={} operationId={} paymentAttemptId={}",
                PAYMENT_SUCCEEDED, context.correlationId(), id, command.paymentAttemptId());
        return result.transactionId();
    }

    private PaymentResult executePaymentWithRetry(
            PurchaseSagaContext context, PaymentCommand command) {
        UUID id = context.operation().operationId();
        for (int attemptNumber = 1; attemptNumber <= PURCHASE_PAYMENT_MAX_ATTEMPTS; attemptNumber++) {
            PaymentAttempt currentAttempt = paymentAttemptStore.startAttempt(id);
            log.info("event={} correlationId={} operationId={} paymentAttemptId={} attemptNumber={}",
                    PAYMENT_ATTEMPT_STARTED, context.correlationId(), id,
                    currentAttempt.getId(), currentAttempt.getAttemptCount());
            try {
                return paymentGateway.charge(command);
            } catch (PaymentDeclinedException declined) {
                throw failureHandler.handleDecline(context, command);
            } catch (PaymentProviderException failure) {
                if (shouldRetry(failure, attemptNumber)) {
                    recordAndWaitBeforeRetry(context, command, failure, attemptNumber);
                    continue;
                }
                throw failureHandler.handleProviderFailure(context, command, failure);
            }
        }
        throw new IllegalStateException("Payment retry loop exited unexpectedly");
    }

    private boolean shouldRetry(PaymentProviderException failure, int attemptNumber) {
        return failure.getFailureType() == FailureType.TRANSIENT && attemptNumber < PURCHASE_PAYMENT_MAX_ATTEMPTS;
    }

    private void recordAndWaitBeforeRetry(PurchaseSagaContext context, PaymentCommand command,
            PaymentProviderException failure, int attemptNumber) {
        UUID id = context.operation().operationId();
        paymentAttemptStore.recordRetryableFailure(id, failure.getFailureType(), failure.getMessage());
        log.info("event={} correlationId={} operationId={} paymentAttemptId={} nextAttempt={} failureType={}",
                PAYMENT_ATTEMPT_RETRY, context.correlationId(), id, command.paymentAttemptId(),
                attemptNumber + 1, failure.getFailureType());
        pauseWithJitter(attemptNumber - 1);
    }

    private void pauseWithJitter(int retryIndex) {
        long delay = Math.min(120L, 15L << retryIndex) + ThreadLocalRandom.current().nextLong(1, 16);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, PAYMENT_RETRY_INTERRUPTED_CODE,
                    "Payment retry was interrupted");
        }
    }
}
