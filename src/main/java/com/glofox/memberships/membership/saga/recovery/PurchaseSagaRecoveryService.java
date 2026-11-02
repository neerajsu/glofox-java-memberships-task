package com.glofox.memberships.membership.saga.recovery;

import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_MDC_KEY;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_ATTEMPT_STARTED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_DECLINED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_OUTCOME_UNKNOWN;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_PROVIDER_FAILED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_SUCCEEDED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_MANUAL_REVIEW;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_BATCH_COMPLETED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_BATCH_STARTED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_CLAIMED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_DEFERRED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_FAILED;
import static com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_FINALIZE_ONLY;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_INTERRUPTED_BEFORE_PAYMENT_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_INTERRUPTED_BEFORE_RESERVATION_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_RECOVERY_MAX_PAYMENT_ATTEMPTS;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_ERROR_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_UNAVAILABLE_CODE;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.orchestration.PurchaseSagaOrchestrator;
import com.glofox.memberships.membership.saga.orchestration.compensation.PurchaseCompensationService;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class PurchaseSagaRecoveryService {

    private final PurchaseSagaStore sagas;
    private final PaymentAttemptStore attempts;
    private final PurchasePaymentOutcomeStore outcomes;
    private final PurchaseSagaOrchestrator orchestrator;
    private final com.glofox.memberships.membership.saga.recovery.payment.RecoveryPaymentGateway gateway;
    private final com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties properties;
    private final PurchaseCompensationService compensation;
    private final PurchaseSagaRecoveryTransaction recoveryTransaction;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    /** Runs one bounded pass; safe for scheduled or manual invocation. */
    public int recoverPending(int requestedBatchSize) {
        int size = Math.max(1, Math.min(properties.getBatchSize(), requestedBatchSize));
        Instant now = Instant.now();
        List<PurchaseSaga> candidates = sagas.findRecoverable(now, size);
        log.info("event={} correlationId={} requestedBatchSize={} batchSize={} candidateCount={}",
                SAGA_RECOVERY_BATCH_STARTED, CorrelationIdContext.currentOr("unknown"),
                requestedBatchSize, size, candidates.size());
        long deadline = System.nanoTime() + properties.getBatchTimeout().toNanos();
        int claimed = 0;
        for (PurchaseSaga candidate : candidates) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
            String previousCorrelationId = MDC.get(CORRELATION_ID_MDC_KEY);
            MDC.put(CORRELATION_ID_MDC_KEY, candidate.getCorrelationId());
            try {
                Instant claimTime = Instant.now();
                if (!sagas.claimForRecovery(candidate, claimTime, claimTime.plus(
                        com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties.CLAIM_LEASE))) continue;
                claimed++;
                PurchaseSaga saga = sagas.findById(candidate.getId());
                log.debug("event={} correlationId={} operationId={} sagaStatus={}",
                        SAGA_RECOVERY_CLAIMED, saga.getCorrelationId(), saga.getId(), saga.getStatus());
                recover(saga);
            } catch (RuntimeException failure) {
                log.error("event={} correlationId={} operationId={} sagaStatus={} exceptionType={}",
                        SAGA_RECOVERY_FAILED, candidate.getCorrelationId(), candidate.getId(), candidate.getStatus(),
                        failure.getClass().getSimpleName(), failure);
                // Failure while reading diagnostic state must not abort the rest of the batch either.
                try {
                    int count = attempts.findOptionalByPurchaseAttemptId(candidate.getId())
                            .map(PaymentAttempt::getAttemptCount).orElse(0);
                    defer(candidate.getId(), candidate.getCorrelationId(), count);
                } catch (RuntimeException diagnosticFailure) {
                    log.error("event={} operationId={} exceptionType={}", SAGA_RECOVERY_FAILED,
                            candidate.getId(), diagnosticFailure.getClass().getSimpleName(), diagnosticFailure);
                }
            } finally {
                if (previousCorrelationId == null) MDC.remove(CORRELATION_ID_MDC_KEY);
                else MDC.put(CORRELATION_ID_MDC_KEY, previousCorrelationId);
            }
        }
        log.info("event={} correlationId={} claimedCount={} candidateCount={}",
                SAGA_RECOVERY_BATCH_COMPLETED, CorrelationIdContext.currentOr("unknown"), claimed, candidates.size());
        return claimed;
    }

    private void recover(PurchaseSaga saga) {
        switch (saga.getStatus()) {
            case INITIATED, VALIDATED -> failBeforeResources(saga);
            case RESOURCES_RESERVED -> recoverReservedResources(saga);
            case PAYMENT_PENDING, PAYMENT_UNKNOWN -> reconcile(saga);
            case PAYMENT_SUCCEEDED -> finalizeConfirmedPayment(saga,
                    attempts.findOptionalByPurchaseAttemptId(saga.getId()).orElseThrow(
                            () -> new IllegalStateException("Confirmed payment attempt was not found")));
            case COMPENSATING -> compensate(saga);
            default -> { }
        }
    }

    private void failBeforeResources(PurchaseSaga saga) {
        ApiException failure = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                PURCHASE_INTERRUPTED_BEFORE_RESERVATION_CODE,
                "Purchase processing was interrupted before resources were reserved");
        optimisticLockRetryPolicy.execute(() -> {
            recoveryTransaction.failBeforeResources(saga.getId(), failure);
            return null;
        });
    }

    private void recoverReservedResources(PurchaseSaga saga) {
        var existingAttempt = attempts.findOptionalByPurchaseAttemptId(saga.getId());
        if (existingAttempt.isPresent()) {
            // The attempt and PAYMENT_PENDING transition are committed atomically. If a legacy or
            // inconsistent record has the attempt but not the state transition, preserve it and reconcile.
            sagas.paymentPending(saga.getId());
            reconcile(sagas.findById(saga.getId()));
            return;
        }

        ApiException failure = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                PURCHASE_INTERRUPTED_BEFORE_PAYMENT_CODE,
                "Purchase processing was interrupted before payment preparation");
        sagas.beginCompensation(saga.getId(), failure);
        compensate(sagas.findById(saga.getId()));
    }

    private void reconcile(PurchaseSaga saga) {
        PaymentAttempt attempt = attempts.findOptionalByPurchaseAttemptId(saga.getId()).orElse(null);
        if (attempt == null) {
            log.error("event={} correlationId={} operationId={} sagaStatus={} issue=payment_attempt_missing",
                    SAGA_RECOVERY_FAILED, saga.getCorrelationId(), saga.getId(), saga.getStatus());
            sagas.manualReview(saga.getId(), "Payment attempt is missing; payment outcome cannot be established safely");
            return;
        }
        if (attempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            outcomes.succeeded(saga.getId(), attempt.getProviderTransactionId());
            finalizeConfirmedPayment(sagas.findById(saga.getId()), attempt);
            return;
        }
        if (attempt.getStatus() == PaymentAttemptStatus.DECLINED) {
            outcomes.declined(saga.getId(), "The payment was declined");
            compensate(sagas.findById(saga.getId()));
            return;
        }
        if (attempt.getStatus() == PaymentAttemptStatus.FAILED) {
            FailureType type = attempt.getLastFailureType() == null
                    ? FailureType.UNKNOWN : attempt.getLastFailureType();
            boolean permanent = type == FailureType.PERMANENT;
            HttpStatus status = permanent ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
            String code = permanent ? PAYMENT_PROVIDER_ERROR_CODE : PAYMENT_PROVIDER_UNAVAILABLE_CODE;
            String message = "The payment provider could not process the charge";
            outcomes.failed(saga.getId(), type, status, code, message);
            compensate(sagas.findById(saga.getId()));
            return;
        }

        if (attempt.getAttemptCount() >= PURCHASE_RECOVERY_MAX_PAYMENT_ATTEMPTS) {
            log.warn("event={} correlationId={} operationId={} attemptCount={}",
                    SAGA_MANUAL_REVIEW, saga.getCorrelationId(), saga.getId(), attempt.getAttemptCount());
            sagas.manualReview(saga.getId(), "Payment outcome could not be confirmed after bounded retries");
            return;
        }

        PaymentAttempt currentAttempt = attempts.startAttempt(saga.getId());
        log.info("event={} correlationId={} operationId={} paymentAttemptId={} attemptNumber={} source=recovery",
                PAYMENT_ATTEMPT_STARTED, saga.getCorrelationId(), saga.getId(), currentAttempt.getId(),
                currentAttempt.getAttemptCount());
        try {
            PaymentResult result = gateway.charge(new PaymentCommand(
                    attempt.getId(), attempt.getProviderIdempotencyKey(), attempt.getAmountCents(), attempt.getMemberName(), saga.getId()));
            outcomes.succeeded(saga.getId(), result.transactionId());
            log.info("event={} correlationId={} operationId={} paymentAttemptId={} recoveryAction=finalize",
                    PAYMENT_SUCCEEDED, saga.getCorrelationId(), saga.getId(), attempt.getId());
            finalizeConfirmedPayment(sagas.findById(saga.getId()),
                    attempts.findByPurchaseAttemptId(saga.getId()));
        } catch (PaymentDeclinedException ex) {
            log.info("event={} correlationId={} operationId={} paymentAttemptId={} source=recovery",
                    PAYMENT_DECLINED, saga.getCorrelationId(), saga.getId(), attempt.getId());
            outcomes.declined(saga.getId(), ex.getMessage());
            compensate(sagas.findById(saga.getId()));
        } catch (PaymentProviderException ex) {
            if (ex.getFailureType() == FailureType.PERMANENT) {
                log.warn("event={} correlationId={} operationId={} paymentAttemptId={} failureType={}",
                        PAYMENT_PROVIDER_FAILED, saga.getCorrelationId(), saga.getId(), attempt.getId(), ex.getFailureType());
                outcomes.failed(saga.getId(), ex.getFailureType(), HttpStatus.BAD_GATEWAY,
                        PAYMENT_PROVIDER_ERROR_CODE, "The payment provider could not process the charge");
                compensate(sagas.findById(saga.getId()));
            } else {
                log.warn("event={} correlationId={} operationId={} paymentAttemptId={} failureType={}",
                        PAYMENT_OUTCOME_UNKNOWN, saga.getCorrelationId(), saga.getId(), attempt.getId(), ex.getFailureType());
                outcomes.unknown(saga.getId(), nextAttempt(currentAttempt.getAttemptCount()), ex.getMessage());
            }
        }
    }

    private void finalizeConfirmedPayment(PurchaseSaga saga, PaymentAttempt attempt) {
        log.info("event={} correlationId={} operationId={}",
                SAGA_RECOVERY_FINALIZE_ONLY, saga.getCorrelationId(), saga.getId());
        PurchaseSaga.PlanPurchaseSnapshot plan = saga.planSnapshot();
        orchestrator.finalizePurchase(saga.getId(), saga.purchaseRequest(), saga.getMemberKey(),
                new PlanPurchaseDetails(plan.planId(), plan.planName(), plan.planType(), plan.priceCents(),
                        plan.credits(), plan.hasFiniteInventory()),
                attempt.getProviderTransactionId(), saga.getClientIdempotencyKey());
    }

    private void compensate(PurchaseSaga saga) {
        compensation.compensate(saga);
    }

    private void defer(java.util.UUID id, String correlationId, int attemptCount) {
        try {
            Instant retryAt = nextAttempt(attemptCount);
            sagas.deferRecovery(id, retryAt);
            log.info("event={} correlationId={} operationId={} attemptCount={} retryAt={}", SAGA_RECOVERY_DEFERRED,
                    correlationId, id, attemptCount, retryAt);
        } catch (RuntimeException failure) {
            log.error("event={} correlationId={} operationId={} attemptCount={} exceptionType={}",
                    SAGA_RECOVERY_DEFERRED, correlationId, id, attemptCount,
                    failure.getClass().getSimpleName(), failure);
        }
    }

    private Instant nextAttempt(int attempts) {
        long base = Math.min(300L, 5L << Math.min(attempts, 5));
        return Instant.now().plusSeconds(base + ThreadLocalRandom.current().nextLong(1, Math.max(2, base / 4 + 1)));
    }
}
