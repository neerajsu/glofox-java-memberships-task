package com.glofox.memberships.membership.saga.orchestration.reservation;

import static com.glofox.memberships.membership.constant.Constants.ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE;

import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** All delegates use REQUIRED propagation and join the retry attempt's transaction. */
@Service
@RequiredArgsConstructor
public class PurchaseResourceReservationTransaction {
    private final UnlimitedMembershipClaimService claims;
    private final InventoryReservationService inventory;
    private final PurchaseSagaStore sagas;

    @Transactional
    public void reserve(UUID id, String memberKey, PlanPurchaseDetails plan) {
        if (plan.type() == PlanType.UNLIMITED) {
            try {
                claims.claim(memberKey, id);
            } catch (DataIntegrityViolationException conflict) {
                // Abort immediately: no queries or other work in the failed transaction.
                throw new MembershipException(HttpStatus.CONFLICT, ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE,
                        "The member already has an active or pending unlimited membership");
            }
        }
        if (plan.hasFiniteInventory()) inventory.reserveOne(plan.id(), id);
        sagas.resourcesReserved(id);
    }
}
