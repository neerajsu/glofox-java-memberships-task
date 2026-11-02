package com.glofox.memberships.membership.dto;

import com.glofox.memberships.membership.repository.entity.MembershipPurchase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

public record PurchaseResponse(
        UUID id,
        String memberName,
        UUID planId,
        String planName,
        String planType,
        int priceCents,
        Integer credits,
        LocalDate startDate,
        String status,
        String paymentTransactionId,
        Instant createdAt) {

    public static PurchaseResponse from(MembershipPurchase purchase) {
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getMemberName(),
                purchase.getPlanId(),
                purchase.getPlanName(),
                purchase.getPlanType().name().toLowerCase(Locale.ROOT),
                purchase.getPriceCents(),
                purchase.getCredits(),
                purchase.getStartDate(),
                purchase.getStatus().name().toLowerCase(Locale.ROOT),
                purchase.getPaymentTransactionId(),
                purchase.getCreatedAt());
    }
}
