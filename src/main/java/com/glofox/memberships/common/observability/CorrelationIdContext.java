package com.glofox.memberships.common.observability;

import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_HEADER;
import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_MDC_KEY;

import java.util.UUID;
import org.slf4j.MDC;

public final class CorrelationIdContext {
    public static final String HEADER = CORRELATION_ID_HEADER;

    private CorrelationIdContext() {}

    public static String currentOr(String fallback) {
        String current = MDC.get(CORRELATION_ID_MDC_KEY);
        return current == null ? fallback : current;
    }

    static String mdcKey() { return CORRELATION_ID_MDC_KEY; }
    static String generated() { return UUID.randomUUID().toString(); }
}
