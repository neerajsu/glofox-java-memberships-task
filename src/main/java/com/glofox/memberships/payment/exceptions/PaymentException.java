package com.glofox.memberships.payment.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PaymentException extends ApiException {
    public PaymentException(HttpStatus status, String code, String message) { super(status, code, message); }
}
