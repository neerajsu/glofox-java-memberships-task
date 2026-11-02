package com.glofox.memberships.plan.repository;

import com.glofox.memberships.plan.repository.entity.Plan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, UUID> {
}
