package com.glofox.memberships.idempotency.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyException extends ApiException {
    public IdempotencyException(HttpStatus status, String code, String message) { super(status, code, message); }
}
