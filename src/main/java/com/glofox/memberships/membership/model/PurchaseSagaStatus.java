package com.glofox.memberships.membership.model;

public enum PurchaseSagaStatus {
    INITIATED,
    VALIDATED,
    RESOURCES_RESERVED,
    PAYMENT_PENDING,
    PAYMENT_UNKNOWN,
    PAYMENT_SUCCEEDED,
    COMPENSATING,
    COMPENSATED,
    FAILED,
    COMPLETED,
    MANUAL_REVIEW
}
