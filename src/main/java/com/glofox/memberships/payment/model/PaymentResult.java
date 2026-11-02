package com.glofox.memberships.payment.model;

/** Transaction reference returned after a confirmed successful charge. */
public record PaymentResult(String transactionId) {
}
