package com.glofox.memberships.membership.saga.orchestration.flow;

import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_RESOURCES_RESERVED;
import static com.glofox.memberships.common.logging.LogEvents.PURCHASE_SAGA_STARTED;

import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.dto.PurchaseResponse;
import com.glofox.memberships.membership.saga.orchestration.payment.PurchasePaymentPreparation;
import com.glofox.memberships.membership.saga.orchestration.payment.PurchasePaymentProcessor;
import com.glofox.memberships.membership.saga.orchestration.reservation.PurchaseResourceReservation;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.membership.service.purchase.PurchaseFinalizer;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.service.PaymentAttemptStore;
import com.glofox.memberships.plan.model.PlanType;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Singleton dependencies only; start always creates a fresh, single-use purchase flow. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseSagaFlowFactory {
    private final PurchaseResourceReservation reservation;
    private final PurchasePaymentPreparation preparation;
    private final PaymentAttemptStore attempts;
    private final PurchasePaymentProcessor payments;
    private final PurchaseFinalizer finalizer;

    public PurchaseSagaFlow start(StartResult operation, PurchaseRequest request, PlanPurchaseDetails plan) {
        var context = new PurchaseSagaContext(operation, request, plan,
                request.memberName().trim().toLowerCase(Locale.ROOT),
                CorrelationIdContext.currentOr(operation.operationId().toString()));
        log.info("event={} correlationId={} operationId={}", PURCHASE_SAGA_STARTED,
                context.correlationId(), operation.operationId());
        return new PurchaseSagaFlow(context);
    }

    /** Not a Spring bean: all persistence is delegated through the injected service proxies. */
    public final class PurchaseSagaFlow {
        private final PurchaseSagaContext context;
        private Step step = Step.STARTED;
        private PaymentCommand paymentCommand;
        private String transactionId;

        private PurchaseSagaFlow(PurchaseSagaContext context) {
            this.context = context;
        }

        public PurchaseSagaFlow reserveResources() {
            begin(Step.STARTED);
            reservation.reserve(context.operation().operationId(), context.memberKey(), context.plan());
            log.debug("event={} correlationId={} operationId={} finiteInventory={} unlimitedClaim={}",
                    PURCHASE_RESOURCES_RESERVED, context.correlationId(), context.operation().operationId(),
                    context.plan().hasFiniteInventory(), context.plan().type() == PlanType.UNLIMITED);
            step = Step.RESERVED;
            return this;
        }

        public PurchaseSagaFlow preparePayment() {
            begin(Step.RESERVED);
            var operation = context.operation();
            preparation.prepare(operation.operationId(), operation.providerKey(), context.request().memberName(),
                    context.plan().priceCents(), context.correlationId());
            var attempt = attempts.findByPurchaseAttemptId(operation.operationId());
            paymentCommand = new PaymentCommand(attempt.getId(), attempt.getProviderIdempotencyKey(),
                    attempt.getAmountCents(), attempt.getMemberName(), operation.operationId());
            step = Step.PREPARED;
            return this;
        }

        public PurchaseSagaFlow chargePayment() {
            begin(Step.PREPARED);
            transactionId = payments.charge(context, paymentCommand);
            step = Step.PAYMENT_CONFIRMED;
            return this;
        }

        public PurchaseResponse finalizePurchase() {
            begin(Step.PAYMENT_CONFIRMED);
            var purchase = finalizer.finalizePurchase(context.operation().operationId(), context.request(),
                    context.memberKey(), context.plan(), transactionId, context.operation().clientKey());
            step = Step.COMPLETED;
            return PurchaseResponse.from(purchase);
        }

        private void begin(Step expected) {
            if (step != expected) {
                throw new IllegalStateException("Purchase flow expected " + expected + " but was " + step);
            }
            // A failed step cannot be repeated on this in-memory flow. Persisted recovery owns resumption.
            step = Step.FAILED;
        }
    }

    private enum Step { STARTED, RESERVED, PREPARED, PAYMENT_CONFIRMED, COMPLETED, FAILED }
}
