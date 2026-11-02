package com.glofox.memberships.plan.repository;

import com.glofox.memberships.plan.repository.entity.PlanInventory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanInventoryRepository extends JpaRepository<PlanInventory, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PlanInventory inventory
               set inventory.availableSlots = inventory.availableSlots - 1,
                   inventory.reservedSlots = inventory.reservedSlots + 1
             where inventory.planId = :planId
               and inventory.availableSlots > 0
            """)
    int reserveOne(@Param("planId") UUID planId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PlanInventory inventory
               set inventory.availableSlots = inventory.availableSlots + 1,
                   inventory.reservedSlots = inventory.reservedSlots - 1
             where inventory.planId = :planId
               and inventory.reservedSlots > 0
            """)
    int releaseOne(@Param("planId") UUID planId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PlanInventory inventory
               set inventory.reservedSlots = inventory.reservedSlots - 1
             where inventory.planId = :planId
               and inventory.reservedSlots > 0
            """)
    int commitOne(@Param("planId") UUID planId);
}
