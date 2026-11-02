package com.glofox.memberships.plan.repository.entity;

import static com.glofox.memberships.plan.constant.Constants.PLAN_TABLE_NAME;

import com.glofox.memberships.plan.model.PlanType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = PLAN_TABLE_NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Plan {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanType type;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    private Integer credits;

}
