package com.glofox.memberships.common.api;

import static com.glofox.memberships.common.constant.Constants.INTERNAL_ERROR_CODE;
import static com.glofox.memberships.common.constant.Constants.INVALID_PARAMETER_CODE;
import static com.glofox.memberships.common.constant.Constants.INVALID_REQUEST_CODE;
import static com.glofox.memberships.common.constant.Constants.NOT_FOUND_CODE;
import static com.glofox.memberships.common.logging.LogEvents.API_REQUEST_FAILED;
import static com.glofox.memberships.common.logging.LogEvents.API_REQUEST_REJECTED;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        log.debug("event={} correlationId={} status={} errorCode={}", API_REQUEST_REJECTED,
                CorrelationIdContext.currentOr("unknown"), exception.getStatus().value(), exception.getCode());
        return ResponseEntity.status(exception.getStatus())
                .body(error(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        log.debug("event={} correlationId={} errorCode={}", API_REQUEST_REJECTED,
                CorrelationIdContext.currentOr("unknown"), INVALID_PARAMETER_CODE);
        return ResponseEntity.badRequest()
                .body(error(INVALID_PARAMETER_CODE, "A path value or query parameter has an invalid format"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(MethodArgumentNotValidException exception) {
        log.debug("event={} correlationId={} errorCode={}", API_REQUEST_REJECTED,
                CorrelationIdContext.currentOr("unknown"), INVALID_REQUEST_CODE);
        return ResponseEntity.badRequest()
                .body(error(INVALID_REQUEST_CODE, "One or more request fields are missing or invalid"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        log.debug("event={} correlationId={} errorCode={}", API_REQUEST_REJECTED,
                CorrelationIdContext.currentOr("unknown"), INVALID_REQUEST_CODE);
        return ResponseEntity.badRequest()
                .body(error(INVALID_REQUEST_CODE, "The request body is malformed or contains invalid values"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissingRoute(NoResourceFoundException exception) {
        log.debug("event={} correlationId={} errorCode={}", API_REQUEST_REJECTED,
                CorrelationIdContext.currentOr("unknown"), NOT_FOUND_CODE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND_CODE, "The requested resource was not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        String correlationId = CorrelationIdContext.currentOr("unknown");
        log.error("event={} correlationId={} exceptionType={}", API_REQUEST_FAILED, correlationId,
                exception.getClass().getSimpleName(), exception);
        return ResponseEntity.internalServerError().body(error(INTERNAL_ERROR_CODE, "An unexpected error occurred"));
    }

    private ApiError error(String code, String message) {
        return new ApiError(code, message, CorrelationIdContext.currentOr("unknown"));
    }
}
