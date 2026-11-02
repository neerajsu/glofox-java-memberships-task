package com.glofox.memberships.plan.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.PlanRepository;
import com.glofox.memberships.plan.repository.entity.Plan;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class PlanCatalogQueryServiceTests {

    @Test
    void mapsSortedPlanEntitiesToCatalogResponsesWithoutInventoryValues() {
        PlanRepository repository = mock(PlanRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findAll(org.mockito.ArgumentMatchers.any(Sort.class))).thenReturn(List.of(
                new Plan(id, "Pack", PlanType.PACK, true, 5_000, 10)));

        var result = new PlanCatalogQueryService(repository).findPlanCatalog();

        assertThat(result).containsExactly(new com.glofox.memberships.plan.dto.PlanResponse(
                id, "Pack", "pack", true, 5_000, 10, null));
    }
}
