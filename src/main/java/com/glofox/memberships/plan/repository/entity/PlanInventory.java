package com.glofox.memberships.plan.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plan_inventory")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlanInventory {

    @Id
    private UUID planId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Setter
    @Column(name = "available_slots", nullable = false)
    private int availableSlots;

    @Column(name = "reserved_slots", nullable = false)
    private int reservedSlots;

    public PlanInventory(Plan plan, int availableSlots) {
        this.plan = plan;
        this.availableSlots = availableSlots;
        this.reservedSlots = 0;
    }

}
