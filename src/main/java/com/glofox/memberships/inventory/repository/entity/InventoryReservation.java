package com.glofox.memberships.inventory.repository.entity;

import com.glofox.memberships.inventory.model.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "inventory_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryReservation {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    public InventoryReservation(UUID id, UUID planId) {
        this.id = id;
        this.planId = planId;
        this.status = ReservationStatus.RESERVED;
    }
}
