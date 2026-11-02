package com.glofox.memberships.membership.service.purchase;

import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;

public record PlanPurchaseDetails(
        UUID id,
        String name,
        PlanType type,
        int priceCents,
        Integer credits,
        boolean hasFiniteInventory) {
}
