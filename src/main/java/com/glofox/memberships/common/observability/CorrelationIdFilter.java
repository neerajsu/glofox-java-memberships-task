package com.glofox.memberships.common.observability;

import static com.glofox.memberships.common.constant.Constants.CORRELATION_ID_VALIDATION_PATTERN;
import static com.glofox.memberships.common.constant.Constants.INVALID_CORRELATION_ID_CODE;

import com.glofox.memberships.common.api.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;

    public CorrelationIdFilter(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(CorrelationIdContext.HEADER);
        String correlationId = supplied == null ? CorrelationIdContext.generated() : supplied;
        if (!CORRELATION_ID_VALIDATION_PATTERN.matcher(correlationId).matches()) {
            correlationId = CorrelationIdContext.generated();
            response.setHeader(CorrelationIdContext.HEADER, correlationId);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                    INVALID_CORRELATION_ID_CODE, "X-Correlation-ID must be 1 to 100 letters, digits, dots, underscores, or hyphens", correlationId));
            return;
        }

        MDC.put(CorrelationIdContext.mdcKey(), correlationId);
        response.setHeader(CorrelationIdContext.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIdContext.mdcKey());
        }
    }
}
