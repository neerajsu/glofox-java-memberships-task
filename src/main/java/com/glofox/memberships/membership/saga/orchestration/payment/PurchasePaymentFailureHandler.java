package com.glofox.memberships.membership.saga.orchestration.payment;

import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_DECLINED;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_OUTCOME_UNKNOWN;
import static com.glofox.memberships.common.logging.LogEvents.PAYMENT_PROVIDER_FAILED;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_ERROR_CODE;
import static com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_UNAVAILABLE_CODE;

import com.glofox.memberships.membership.saga.orchestration.compensation.PurchaseCompensationService;
import com.glofox.memberships.membership.saga.orchestration.flow.PurchaseSagaContext;
import com.glofox.memberships.membership.saga.persistence.PurchasePaymentOutcomeStore;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Persists non-retryable payment outcomes and compensates only definitive failures. */
@Service
@Slf4j
@RequiredArgsConstructor
public class PurchasePaymentFailureHandler {
    private final PurchaseCompensationService compensationService;
    private final PurchaseSagaStore sagaStore;
    private final PurchasePaymentOutcomeStore outcomeStore;

    /**
     * Records a definitive decline, loads the persisted saga and compensates its resources.
     * The returned exception contains the existing sanitized HTTP 402 response; persistence,
     * saga lookup or compensation failures propagate instead of being hidden by that response.
     *
     * @param context stable purchase identifiers and correlation information
     * @param command payment command identifying the provider attempt
     * @return the sanitized decline exception for the caller to throw after successful compensation
     */
    public PaymentDeclinedException handleDecline(PurchaseSagaContext context, PaymentCommand command) {
        UUID id = context.operation().operationId();
        log.info("event={} correlationId={} operationId={} paymentAttemptId={}",
                PAYMENT_DECLINED, context.correlationId(), id, command.paymentAttemptId());
        outcomeStore.declined(id, "The payment was declined");
        compensationService.compensate(sagaStore.findById(id));
        return new PaymentDeclinedException("The payment was declined");
    }

    /**
     * Records a provider outcome that will not be retried synchronously. Unknown outcomes
     * retain their resources, set reconciliation due in 30 seconds and return HTTP 503;
     * the existing recovery eligibility gates still apply.
     * Permanent failures (HTTP 502) and exhausted transient failures (HTTP 503) are recorded
     * before loading the saga and compensating its resources. The caller determines whether
     * transient retries are exhausted. Persistence and compensation failures propagate unchanged.
     *
     * @param context stable purchase identifiers and correlation information
     * @param command payment command identifying the provider attempt
     * @param failure unknown, permanent or exhausted transient provider failure
     * @return the existing mapped exception for the caller to throw
     */
    public PaymentException handleProviderFailure(PurchaseSagaContext context, PaymentCommand command,
            PaymentProviderException failure) {
        UUID id = context.operation().operationId();
        if (failure.getFailureType() == FailureType.UNKNOWN) {
            log.warn("event={} correlationId={} operationId={} paymentAttemptId={} failureType={}",
                    PAYMENT_OUTCOME_UNKNOWN, context.correlationId(), id, command.paymentAttemptId(), failure.getFailureType());
            outcomeStore.unknown(id, Instant.now().plusSeconds(30), failure.getMessage());
            return new PaymentException(HttpStatus.SERVICE_UNAVAILABLE, PAYMENT_OUTCOME_UNKNOWN_CODE,
                    "The payment outcome is being reconciled");
        }

        HttpStatus status = failure.getFailureType() == FailureType.PERMANENT
                ? HttpStatus.BAD_GATEWAY : HttpStatus.SERVICE_UNAVAILABLE;
        String code = failure.getFailureType() == FailureType.PERMANENT
                ? PAYMENT_PROVIDER_ERROR_CODE : PAYMENT_PROVIDER_UNAVAILABLE_CODE;
        PaymentException mappedFailure = new PaymentException(status, code, "The payment provider could not process the charge");
        log.warn("event={} correlationId={} operationId={} paymentAttemptId={} failureType={} status={} errorCode={}",
                PAYMENT_PROVIDER_FAILED, context.correlationId(), id, command.paymentAttemptId(),
                failure.getFailureType(), status.value(), code);
        outcomeStore.failed(id, failure.getFailureType(), status, code, mappedFailure.getMessage());
        compensationService.compensate(sagaStore.findById(id));
        return mappedFailure;
    }
}
