package com.glofox.memberships.common.constant;

import java.util.regex.Pattern;

/** Central names and protocol values for the common service. */
public final class Constants {
    private Constants() {}

    public static final String HTTP_GET_METHOD = "GET";
    public static final String API_JSON_CONTENT_TYPE = "application/json";
    public static final String INTERNAL_API_V1_PATH = "/internal/v1";

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final Pattern CORRELATION_ID_VALIDATION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    public static final String OPTIMISTIC_LOCK_RETRY_EXHAUSTED_CODE = "OPTIMISTIC_LOCK_RETRY_EXHAUSTED";
    public static final String INVALID_REQUEST_CODE = "INVALID_REQUEST";
    public static final String INVALID_PARAMETER_CODE = "INVALID_PARAMETER";
    public static final String NOT_FOUND_CODE = "NOT_FOUND";
    public static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";
    public static final String INVALID_CORRELATION_ID_CODE = "INVALID_CORRELATION_ID";
}
