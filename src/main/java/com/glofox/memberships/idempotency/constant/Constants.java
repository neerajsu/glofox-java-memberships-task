package com.glofox.memberships.idempotency.constant;

/** Stable idempotency contract codes and key prefixes. */
public final class Constants {
    private Constants() {}

    public static final String KEY_REUSED_CODE = "IDEMPOTENCY_KEY_REUSED";
    public static final String IN_PROGRESS_CODE = "IDEMPOTENCY_IN_PROGRESS";
    public static final String KEY_REQUIRED_CODE = "IDEMPOTENCY_KEY_REQUIRED";
    public static final String INVALID_KEY_CODE = "INVALID_IDEMPOTENCY_KEY";

    public static final String PROVIDER_KEY_PREFIX = "membership-payment-";
}
