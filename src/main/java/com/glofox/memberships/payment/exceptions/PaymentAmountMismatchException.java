package com.glofox.memberships.payment.exceptions;

/** Internal simulated-provider contract violation; it does not establish that no charge occurred. */
public class PaymentAmountMismatchException extends RuntimeException {
    public PaymentAmountMismatchException() {
        super("The simulated provider key was reused with a different amount");
    }
}
