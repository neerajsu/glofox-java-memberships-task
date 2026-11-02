package com.glofox.memberships.payment.model;

import java.util.UUID;

/** Stable payment-attempt identifiers, provider idempotency key and charge details. */
public record PaymentCommand(
        UUID paymentAttemptId,
        String idempotencyKey,
        int amountCents,
        String memberName,
        UUID purchaseAttemptId) {
}
