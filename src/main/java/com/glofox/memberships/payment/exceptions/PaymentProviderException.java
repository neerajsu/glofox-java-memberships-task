package com.glofox.memberships.payment.exceptions;

import com.glofox.memberships.payment.model.FailureType;
import lombok.Getter;

@Getter
public class PaymentProviderException extends RuntimeException {
    private final FailureType failureType;
    public PaymentProviderException(FailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }
}
