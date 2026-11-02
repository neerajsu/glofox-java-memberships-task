package com.glofox.memberships.plan.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.plan.dto.PlanResponse;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanQueryServiceTests {

    @Test
    void readsInventoryDynamicallyAndLeavesUnlimitedPlansWithoutSlotCounts() {
        UUID packId = UUID.randomUUID();
        UUID unlimitedId = UUID.randomUUID();
        PlanCatalogQueryService catalog = mock(PlanCatalogQueryService.class);
        PlanInventoryRepository inventory = mock(PlanInventoryRepository.class);
        PlanInventory finiteInventory = mock(PlanInventory.class);
        when(finiteInventory.getPlanId()).thenReturn(packId);
        when(finiteInventory.getAvailableSlots()).thenReturn(2);
        when(inventory.findAll()).thenReturn(List.of(finiteInventory));
        when(catalog.findPlanCatalog()).thenReturn(List.of(
                new PlanResponse(packId, "Pack", "pack", true, 5_000, 10, null),
                new PlanResponse(unlimitedId, "Unlimited", "unlimited", true, 8_000, null, null)));

        List<PlanResponse> result = new PlanQueryService(catalog, inventory).findAll();

        assertThat(result).extracting(PlanResponse::remainingSlots).containsExactly(2, null);
    }
}
