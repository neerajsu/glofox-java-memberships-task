package com.glofox.memberships.inventory.service.reservation;

import static com.glofox.memberships.inventory.constant.Constants.INVENTORY_EXHAUSTED_CODE;

import com.glofox.memberships.inventory.exceptions.InventoryException;
import com.glofox.memberships.inventory.repository.entity.InventoryReservation;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryReservationWriter {
    private final PlanInventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    @Transactional public void reserveOne(UUID planId, UUID purchaseId) {
        if (inventoryRepository.reserveOne(planId) != 1) {
            throw new InventoryException(HttpStatus.CONFLICT, INVENTORY_EXHAUSTED_CODE, "The selected plan has no remaining inventory");
        }
        reservationRepository.saveAndFlush(new InventoryReservation(purchaseId, planId));
    }
    @Transactional public int release(UUID planId) { return inventoryRepository.releaseOne(planId); }
    @Transactional public int commit(UUID planId) { return inventoryRepository.commitOne(planId); }
}
