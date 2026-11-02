package com.glofox.memberships.membership.dto;

import java.util.List;

public record PurchasePageResponse(
        List<PurchaseResponse> content,
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasMore) {
}
