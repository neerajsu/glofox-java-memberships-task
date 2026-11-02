package com.glofox.memberships.common.logging;

/** Stable event names used to make application logs easy to filter and group. */
public final class LogEvents {
    private LogEvents() {}

    public static final String PURCHASE_REPLAYED = "membership.purchase.replayed";
    public static final String PURCHASE_FAILED = "membership.purchase.failed";
    public static final String PURCHASE_SAGA_STARTED = "membership.purchase.saga.started";
    public static final String PURCHASE_RESOURCES_RESERVED = "membership.purchase.resources.reserved";
    public static final String PAYMENT_ATTEMPT_STARTED = "membership.payment.attempt.started";
    public static final String PAYMENT_ATTEMPT_RETRY = "membership.payment.attempt.retry";
    public static final String PAYMENT_SUCCEEDED = "membership.payment.succeeded";
    public static final String PAYMENT_DECLINED = "membership.payment.declined";
    public static final String PAYMENT_OUTCOME_UNKNOWN = "membership.payment.outcome.unknown";
    public static final String PAYMENT_PROVIDER_FAILED = "membership.payment.provider.failed";
    public static final String PURCHASE_FINALIZED = "membership.purchase.finalized";
    public static final String PURCHASE_COMPENSATION_STARTED = "membership.purchase.compensation.started";
    public static final String PURCHASE_COMPENSATION_COMPLETED = "membership.purchase.compensation.completed";
    public static final String SAGA_RECOVERY_BATCH_STARTED = "membership.saga.recovery.batch.started";
    public static final String SAGA_RECOVERY_BATCH_COMPLETED = "membership.saga.recovery.batch.completed";
    public static final String SAGA_RECOVERY_CLAIMED = "membership.saga.recovery.claimed";
    public static final String SAGA_RECOVERY_FINALIZE_ONLY = "membership.saga.recovery.finalize_only";
    public static final String SAGA_RECOVERY_FAILED = "membership.saga.recovery.failed";
    public static final String SAGA_RECOVERY_DEFERRED = "membership.saga.recovery.deferred";
    public static final String SAGA_MANUAL_REVIEW = "membership.saga.recovery.manual_review";
    public static final String OPTIMISTIC_LOCK_RETRY = "persistence.optimistic_lock.retry";
    public static final String OPTIMISTIC_LOCK_RETRY_EXHAUSTED = "persistence.optimistic_lock.exhausted";
    public static final String API_REQUEST_REJECTED = "api.request.rejected";
    public static final String API_REQUEST_FAILED = "api.request.failed";
    public static final String IDEMPOTENCY_RECORD_CREATED = "membership.idempotency.record.created";
    public static final String IDEMPOTENCY_CONFLICT = "membership.idempotency.conflict";
    public static final String APPLICATION_SEED_COMPLETED = "application.seed.plans.completed";
    public static final String APPLICATION_SEED_PLAN = "application.seed.plan";
}
