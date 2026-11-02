package com.glofox.memberships.payment.model;

public enum PaymentAttemptStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    DECLINED,
    FAILED,
    UNKNOWN
}
