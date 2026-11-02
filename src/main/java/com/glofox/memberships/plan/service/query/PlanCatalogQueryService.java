package com.glofox.memberships.plan.service.query;

import static com.glofox.memberships.plan.constant.Constants.PLANS_CACHE;
import static com.glofox.memberships.plan.constant.Constants.PLANS_LIST_CACHE_KEY;

import com.glofox.memberships.plan.dto.PlanResponse;
import com.glofox.memberships.plan.repository.entity.Plan;
import com.glofox.memberships.plan.repository.PlanRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cached plan catalog data. Inventory is deliberately read dynamically by {@link PlanQueryService}. */
@Service
@RequiredArgsConstructor
public class PlanCatalogQueryService {
    private final PlanRepository planRepository;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = PLANS_CACHE, key = "'" + PLANS_LIST_CACHE_KEY + "'")
    public List<PlanResponse> findPlanCatalog() {
        return planRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::toCatalogResponse)
                .toList();
    }

    private PlanResponse toCatalogResponse(Plan plan) {
        return new PlanResponse(plan.getId(), plan.getName(), plan.getType().name().toLowerCase(Locale.ROOT),
                plan.isActive(), plan.getPriceCents(), plan.getCredits(), null);
    }
}
