package com.glofox.memberships.membership.saga.persistence;

import static com.glofox.memberships.membership.constant.Constants.PAYMENT_DECLINED_CODE;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentAttemptStatus;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.payment.repository.entity.PaymentAttempt;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PurchasePaymentOutcomeStore {

    private final PurchaseSagaRepository sagaRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public void succeeded(UUID operationId, String transactionId) {
        optimisticLockRetryPolicy.execute(() -> {
            PaymentAttempt payment = paymentAttempt(operationId);
            PurchaseSaga saga = saga(operationId);
            if (payment.getStatus() == PaymentAttemptStatus.SUCCEEDED
                    && saga.getStatus() == PurchaseSagaStatus.PAYMENT_SUCCEEDED
                    && transactionId.equals(payment.getProviderTransactionId())) return null;
            if (saga.getStatus() == PurchaseSagaStatus.COMPLETED) {
                if (transactionId.equals(payment.getProviderTransactionId())) return null;
                throw new IllegalStateException("A different payment transaction is already finalized");
            }
            payment.markSucceeded(transactionId);
            saga.markPaymentSucceeded();
            return null;
        });
    }

    public void unknown(UUID operationId, Instant retryAt, String message) {
        optimisticLockRetryPolicy.execute(() -> {
            PaymentAttempt payment = paymentAttempt(operationId);
            PurchaseSaga saga = saga(operationId);
            if (payment.getStatus() == PaymentAttemptStatus.SUCCEEDED
                    || saga.getStatus() == PurchaseSagaStatus.PAYMENT_SUCCEEDED
                    || saga.getStatus() == PurchaseSagaStatus.COMPLETED) return null;
            payment.markUnknown(message);
            saga.markPaymentUnknown(retryAt, message);
            return null;
        });
    }

    public void declined(UUID operationId, String message) {
        optimisticLockRetryPolicy.execute(() -> {
            PaymentAttempt payment = paymentAttempt(operationId);
            PurchaseSaga saga = saga(operationId);
            if (payment.getStatus() == PaymentAttemptStatus.DECLINED
                    && saga.getStatus() == PurchaseSagaStatus.COMPENSATING) return null;
            if (payment.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
                throw new IllegalStateException("A confirmed payment cannot be changed to declined");
            }
            payment.markDeclined(message);
            saga.beginCompensation(HttpStatus.PAYMENT_REQUIRED, PAYMENT_DECLINED_CODE, "The payment was declined");
            return null;
        });
    }

    public void failed(UUID operationId, FailureType type, HttpStatus status, String code, String message) {
        optimisticLockRetryPolicy.execute(() -> {
            PaymentAttempt payment = paymentAttempt(operationId);
            PurchaseSaga saga = saga(operationId);
            if (payment.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
                throw new IllegalStateException("A confirmed payment cannot be changed to failed");
            }
            if (payment.getStatus() == PaymentAttemptStatus.FAILED
                    && saga.getStatus() == PurchaseSagaStatus.COMPENSATING) return null;
            payment.markFailed(type, message);
            saga.beginCompensation(status, code, message);
            return null;
        });
    }

    private PaymentAttempt paymentAttempt(UUID operationId) {
        return paymentAttemptRepository.findByPurchaseAttemptId(operationId)
                .orElseThrow(() -> new IllegalStateException("Payment attempt was not found"));
    }

    private PurchaseSaga saga(UUID operationId) {
        return sagaRepository.findById(operationId)
                .orElseThrow(() -> new IllegalStateException("Purchase saga was not found"));
    }
}
