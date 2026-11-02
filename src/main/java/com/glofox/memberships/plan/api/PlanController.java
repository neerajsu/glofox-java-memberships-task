package com.glofox.memberships.plan.api;

import static com.glofox.memberships.plan.constant.Constants.PLANS_PATH;

import com.glofox.memberships.plan.dto.PlanResponse;
import com.glofox.memberships.plan.service.query.PlanQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PLANS_PATH)
@RequiredArgsConstructor
public class PlanController {

    private final PlanQueryService planQueryService;

    @GetMapping
    public List<PlanResponse> getPlans() {
        return planQueryService.findAll();
    }
}
