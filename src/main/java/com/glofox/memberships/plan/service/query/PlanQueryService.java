package com.glofox.memberships.plan.service.query;

import com.glofox.memberships.plan.dto.PlanResponse;
import com.glofox.memberships.plan.repository.entity.PlanInventory;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanQueryService {

    private final PlanCatalogQueryService planCatalog;
    private final PlanInventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public List<PlanResponse> findAll() {
        Map<UUID, PlanInventory> inventoryByPlanId = inventoryRepository.findAll().stream()
                .collect(Collectors.toMap(PlanInventory::getPlanId, Function.identity()));

        return planCatalog.findPlanCatalog().stream()
                .map(plan -> new PlanResponse(plan.id(), plan.name(), plan.type(), plan.active(), plan.priceCents(),
                        plan.credits(), inventoryByPlanId.containsKey(plan.id())
                                ? inventoryByPlanId.get(plan.id()).getAvailableSlots() : null))
                .toList();
    }
}
