package com.glofox.memberships.inventory.repository;

import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InventoryReservation reservation
               set reservation.status = :newStatus
             where reservation.id = :id
               and reservation.status = :expectedStatus
            """)
    int transition(
            @Param("id") UUID id,
            @Param("expectedStatus") ReservationStatus expectedStatus,
            @Param("newStatus") ReservationStatus newStatus);
}
