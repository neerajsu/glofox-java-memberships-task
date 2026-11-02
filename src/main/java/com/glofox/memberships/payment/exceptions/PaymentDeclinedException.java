package com.glofox.memberships.payment.exceptions;

import static com.glofox.memberships.membership.constant.Constants.PAYMENT_DECLINED_CODE;

import org.springframework.http.HttpStatus;

public class PaymentDeclinedException extends PaymentException {
    public PaymentDeclinedException(String message) {
        super(HttpStatus.PAYMENT_REQUIRED, PAYMENT_DECLINED_CODE, message);
    }
}
