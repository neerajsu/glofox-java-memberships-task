package com.glofox.memberships.plan.dto;

import java.util.UUID;

public record PlanResponse(
        UUID id,
        String name,
        String type,
        boolean active,
        int priceCents,
        Integer credits,
        Integer remainingSlots) {
}
