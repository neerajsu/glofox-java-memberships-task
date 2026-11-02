package com.glofox.memberships.inventory.service.reservation;

import static com.glofox.memberships.inventory.constant.Constants.INVENTORY_TEMPORARILY_UNAVAILABLE_CODE;
import static com.glofox.memberships.testharness.constant.Constants.INVENTORY_FAILURE_COUNT_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.INVENTORY_RESERVE_OPERATION;

import com.glofox.memberships.inventory.exceptions.InventoryException;
import com.glofox.memberships.inventory.model.ReservationStatus;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import com.glofox.memberships.testharness.annotation.FaultInjectable;
import com.glofox.memberships.testharness.model.FaultScenario;
import com.glofox.memberships.testharness.service.FaultInvocationContext;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryReservationService {

    private final InventoryReservationRepository reservationRepository;
    private final ObjectProvider<TestHarnessSettingsService> harnessSettings;
    private final InventoryReservationWriter writer;

    @Transactional
    @FaultInjectable(value = INVENTORY_RESERVE_OPERATION, simulationMethod = "simulateReserveOne")
    public void reserveOne(UUID planId, UUID purchaseId) {
        writer.reserveOne(planId, purchaseId);
    }

    public void simulateReserveOne(UUID planId, UUID purchaseId) {
        FaultScenario scenario = FaultInvocationContext.current();
        TestHarnessSettingsService settings = harnessSettings.getObject();
        if (scenario == FaultScenario.INVENTORY_UNAVAILABLE || scenario == FaultScenario.INVENTORY_TRANSIENT_ERROR) {
            boolean configured = settings.counterConfigured(INVENTORY_FAILURE_COUNT_SETTING);
            if (!configured || settings.consumeCounter(INVENTORY_FAILURE_COUNT_SETTING)) {
                throw new InventoryException(HttpStatus.SERVICE_UNAVAILABLE, INVENTORY_TEMPORARILY_UNAVAILABLE_CODE,
                        "Inventory reservation is temporarily unavailable");
            }
        }
        writer.reserveOne(planId, purchaseId);
    }

    @Transactional
    public void commit(UUID purchaseId) {
        transition(purchaseId, ReservationStatus.COMMITTED);
    }

    @Transactional
    public void release(UUID purchaseId) {
        transition(purchaseId, ReservationStatus.RELEASED);
    }

    private void transition(UUID purchaseId, ReservationStatus targetStatus) {
        InventoryReservation reservation = reservationRepository.findById(purchaseId)
                .orElseThrow(() -> new IllegalStateException("Inventory reservation was not found"));
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            return;
        }

        if (reservationRepository.transition(purchaseId, ReservationStatus.RESERVED, targetStatus) != 1) {
            return;
        }

        int inventoryUpdated = targetStatus == ReservationStatus.RELEASED
                ? writer.release(reservation.getPlanId())
                : writer.commit(reservation.getPlanId());
        if (inventoryUpdated != 1) {
            throw new IllegalStateException("Reserved inventory count was inconsistent");
        }
    }
}
