package com.glofox.memberships.common.observability;

import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdContextTests {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void returnsMdcCorrelationIdWhenPresent() {
        MDC.put(CORRELATION_ID_MDC_KEY, "request-123");

        assertThat(CorrelationIdContext.currentOr("fallback")).isEqualTo("request-123");
    }

    @Test
    void returnsFallbackWhenRequestHasNoCorrelationId() {
        assertThat(CorrelationIdContext.currentOr("fallback")).isEqualTo("fallback");
    }
}
