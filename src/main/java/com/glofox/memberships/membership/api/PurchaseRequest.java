package com.glofox.memberships.membership.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseRequest(
        @NotBlank @Size(max = 160) String memberName,
        @NotNull UUID planId,
        @NotNull LocalDate startDate) {
}
