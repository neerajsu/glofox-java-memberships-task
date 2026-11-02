package com.glofox.memberships.inventory.service.reservation;

import static com.glofox.memberships.inventory.constant.Constants.INVENTORY_EXHAUSTED_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.inventory.exceptions.InventoryException;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryReservationWriterTests {

    @Test
    void reservesInventoryBeforePersistingReservation() {
        PlanInventoryRepository inventory = mock(PlanInventoryRepository.class);
        InventoryReservationRepository reservations = mock(InventoryReservationRepository.class);
        when(inventory.reserveOne(any())).thenReturn(1);
        UUID planId = UUID.randomUUID();
        UUID purchaseId = UUID.randomUUID();

        new InventoryReservationWriter(inventory, reservations).reserveOne(planId, purchaseId);

        verify(reservations).saveAndFlush(any(InventoryReservation.class));
    }

    @Test
    void doesNotPersistReservationWhenInventoryIsExhausted() {
        PlanInventoryRepository inventory = mock(PlanInventoryRepository.class);
        InventoryReservationRepository reservations = mock(InventoryReservationRepository.class);
        when(inventory.reserveOne(any())).thenReturn(0);

        assertThatThrownBy(() -> new InventoryReservationWriter(inventory, reservations)
                .reserveOne(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(InventoryException.class)
                .satisfies(failure -> assertThat(((InventoryException) failure).getCode())
                        .isEqualTo(INVENTORY_EXHAUSTED_CODE));
        verify(reservations, never()).saveAndFlush(any(InventoryReservation.class));
    }

    @Test
    void delegatesInventoryCommitAndReleaseCounts() {
        PlanInventoryRepository inventory = mock(PlanInventoryRepository.class);
        InventoryReservationRepository reservations = mock(InventoryReservationRepository.class);
        when(inventory.commitOne(any())).thenReturn(1);
        when(inventory.releaseOne(any())).thenReturn(1);
        InventoryReservationWriter writer = new InventoryReservationWriter(inventory, reservations);
        UUID planId = UUID.randomUUID();

        assertThat(writer.commit(planId)).isEqualTo(1);
        assertThat(writer.release(planId)).isEqualTo(1);
    }
}
