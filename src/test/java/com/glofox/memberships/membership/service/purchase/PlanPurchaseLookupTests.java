package com.glofox.memberships.membership.service.purchase;

import static com.glofox.memberships.plan.constant.Constants.PLAN_INACTIVE_CODE;
import static com.glofox.memberships.plan.constant.Constants.PLAN_NOT_FOUND_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.plan.exceptions.PlanException;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.plan.repository.PlanRepository;
import com.glofox.memberships.plan.repository.entity.Plan;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PlanPurchaseLookupTests {

    private final PlanRepository plans = mock(PlanRepository.class);
    private final PlanInventoryRepository inventory = mock(PlanInventoryRepository.class);
    private final PlanPurchaseLookup lookup = new PlanPurchaseLookup(plans, inventory);

    @Test
    void returnsPurchaseSnapshotAndFiniteInventoryFlagForActivePlan() {
        UUID id = UUID.randomUUID();
        when(plans.findById(id)).thenReturn(Optional.of(
                new Plan(id, "Pack", PlanType.PACK, true, 5_000, 10)));
        when(inventory.existsById(id)).thenReturn(true);

        PlanPurchaseDetails result = lookup.findActivePlan(id);

        assertThat(result).isEqualTo(new PlanPurchaseDetails(id, "Pack", PlanType.PACK, 5_000, 10, true));
    }

    @Test
    void returnsNotFoundForUnknownPlan() {
        UUID id = UUID.randomUUID();
        when(plans.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.findActivePlan(id))
                .isInstanceOf(PlanException.class)
                .satisfies(failure -> {
                    PlanException exception = (PlanException) failure;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo(PLAN_NOT_FOUND_CODE);
                });
    }

    @Test
    void rejectsInactivePlanWithoutCheckingInventory() {
        UUID id = UUID.randomUUID();
        when(plans.findById(id)).thenReturn(Optional.of(
                new Plan(id, "Old", PlanType.PACK, false, 5_000, 10)));

        assertThatThrownBy(() -> lookup.findActivePlan(id))
                .isInstanceOf(PlanException.class)
                .satisfies(failure -> {
                    PlanException exception = (PlanException) failure;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo(PLAN_INACTIVE_CODE);
                });
        verify(inventory, org.mockito.Mockito.never()).existsById(id);
    }
}
