package com.glofox.memberships.membership.constant;

import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import java.util.List;

/** Central names and protocol values for the membership service. */
public final class Constants {
    private Constants() {}

    public static final String PURCHASE_COLLECTION_PATH = "/v1/memberships/purchases";
    public static final String PURCHASE_BY_ID_PATH = "/v1/memberships/purchases/{id}";

    public static final String PURCHASE_NOT_FOUND_CODE = "PURCHASE_NOT_FOUND";
    public static final String PURCHASE_SAGA_NOT_FOUND_CODE = "PURCHASE_SAGA_NOT_FOUND";

    public static final String INVALID_PAGE_CODE = "INVALID_PAGE";
    public static final String INVALID_LIMIT_CODE = "INVALID_LIMIT";

    public static final String PAYMENT_OUTCOME_UNKNOWN_CODE = "PAYMENT_OUTCOME_UNKNOWN";
    public static final String PAYMENT_RECONCILIATION_REQUIRED_CODE = "PAYMENT_RECONCILIATION_REQUIRED";
    public static final String PAYMENT_DECLINED_CODE = com.glofox.memberships.payment.constant.Constants.PAYMENT_DECLINED_CODE;

    public static final String ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE = "ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS";
    public static final String MEMBERSHIP_ACTIVATION_FAILED_CODE = "MEMBERSHIP_ACTIVATION_FAILED";
    public static final String COMPLETED_STATUS = "COMPLETED";

    public static final String PURCHASE_INTERRUPTED_BEFORE_RESERVATION_CODE = "PURCHASE_INTERRUPTED_BEFORE_RESERVATION";
    public static final String PURCHASE_INTERRUPTED_BEFORE_PAYMENT_CODE = "PURCHASE_INTERRUPTED_BEFORE_PAYMENT";
    public static final long PURCHASE_RECOVERY_GRACE_SECONDS = 30;
    public static final String RECOVERY_PROPERTY_PREFIX = "app.saga.recovery";
    public static final String RECOVERY_CRON = "${app.saga.recovery.cron:*/15 * * * * *}";
    public static final String RECOVERY_LOCK_DURATION = "${app.saga.recovery.lock-at-most-for:PT5M}";
    public static final String RECOVERY_LOCK_NAME = "purchaseSagaRecovery";

    // Purchase query pagination.
    public static final int PURCHASE_QUERY_DEFAULT_PAGE = 0;
    public static final int PURCHASE_QUERY_DEFAULT_LIMIT = 20;
    public static final int PURCHASE_QUERY_MAX_LIMIT = 100;

    // Total provider calls: synchronous processing and recovery have separate budgets.
    public static final int PURCHASE_PAYMENT_MAX_ATTEMPTS = 3;
    public static final int PURCHASE_RECOVERY_MAX_PAYMENT_ATTEMPTS = 6;

    // Immutable states eligible for persisted recovery.
    public static final List<PurchaseSagaStatus> PURCHASE_SAGA_RECOVERABLE_STATUSES = List.of(
            PurchaseSagaStatus.INITIATED,
            PurchaseSagaStatus.VALIDATED,
            PurchaseSagaStatus.RESOURCES_RESERVED,
            PurchaseSagaStatus.PAYMENT_PENDING,
            PurchaseSagaStatus.PAYMENT_SUCCEEDED,
            PurchaseSagaStatus.PAYMENT_UNKNOWN,
            PurchaseSagaStatus.COMPENSATING);
}
