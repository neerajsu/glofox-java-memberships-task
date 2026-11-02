package com.glofox.memberships.common.api;

import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_MDC_KEY;
import static com.glofox.memberships.common.constant.Constants.INTERNAL_ERROR_CODE;
import static com.glofox.memberships.common.constant.Constants.INVALID_PARAMETER_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTests {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesApiErrorStatusCodeAndCorrelationId() {
        MDC.put(CORRELATION_ID_MDC_KEY, "test-correlation");

        var response = handler.handleApiException(new ApiException(HttpStatus.CONFLICT, "CONFLICT", "conflict"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ApiError("CONFLICT", "conflict", "test-correlation"));
    }

    @Test
    void mapsUnexpectedFailureToGenericInternalError() {
        var response = handler.handleUnexpected(new IllegalStateException("private details"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ApiError(INTERNAL_ERROR_CODE,
                "An unexpected error occurred", CorrelationIdContext.currentOr("unknown")));
        assertThat(response.getBody().message()).doesNotContain("private details");
    }
}
