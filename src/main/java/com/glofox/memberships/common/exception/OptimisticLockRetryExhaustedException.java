package com.glofox.memberships.common.exception;

import static com.glofox.memberships.common.constant.Constants.OPTIMISTIC_LOCK_RETRY_EXHAUSTED_CODE;

import org.springframework.http.HttpStatus;

public class OptimisticLockRetryExhaustedException extends ApiException {
    public OptimisticLockRetryExhaustedException(Throwable cause) {
        super(HttpStatus.CONFLICT, OPTIMISTIC_LOCK_RETRY_EXHAUSTED_CODE,
                "The operation could not be completed because its state kept changing");
        initCause(cause);
    }
}
