package com.glofox.memberships.inventory.service.reservation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class InventoryReservationServiceTests {

    private final InventoryReservationRepository reservations = mock(InventoryReservationRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TestHarnessSettingsService> harness = mock(ObjectProvider.class);
    private final PlanInventoryRepository planInventory = mock(PlanInventoryRepository.class);
    private final InventoryReservationWriter writer = new InventoryReservationWriter(planInventory, reservations);
    private final InventoryReservationService service = new InventoryReservationService(reservations, harness, writer);

    @Test
    void commitTransitionsReservationAndCommitsReservedSlotExactlyOnce() {
        UUID purchaseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        when(reservations.findById(purchaseId)).thenReturn(Optional.of(new InventoryReservation(purchaseId, planId)));
        when(reservations.transition(purchaseId, ReservationStatus.RESERVED, ReservationStatus.COMMITTED)).thenReturn(1, 0);
        when(planInventory.commitOne(planId)).thenReturn(1);

        service.commit(purchaseId);
        service.commit(purchaseId);

        verify(planInventory).commitOne(planId);
    }

    @Test
    void repeatedTransitionAfterReservationIsNoLongerReservedDoesNotUpdateInventory() {
        UUID purchaseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        when(reservations.findById(purchaseId)).thenReturn(Optional.of(new InventoryReservation(purchaseId, planId)));
        when(reservations.transition(purchaseId, ReservationStatus.RESERVED, ReservationStatus.RELEASED)).thenReturn(0);

        service.release(purchaseId);

        verify(planInventory, never()).releaseOne(planId);
    }

    @Test
    void throwsWhenReservationAndInventoryCountersAreInconsistent() {
        UUID purchaseId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        when(reservations.findById(purchaseId)).thenReturn(Optional.of(new InventoryReservation(purchaseId, planId)));
        when(reservations.transition(purchaseId, ReservationStatus.RESERVED, ReservationStatus.RELEASED)).thenReturn(1);
        when(planInventory.releaseOne(planId)).thenReturn(0);

        assertThatThrownBy(() -> service.release(purchaseId)).isInstanceOf(IllegalStateException.class);
    }
}
