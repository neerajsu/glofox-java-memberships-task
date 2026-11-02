package com.glofox.memberships.membership.saga.orchestration.reservation;

import static com.glofox.memberships.membership.constant.Constants.ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.claim.UnlimitedMembershipClaimService;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class PurchaseResourceReservationTransactionTests {

    private final UnlimitedMembershipClaimService claims = mock(UnlimitedMembershipClaimService.class);
    private final InventoryReservationService inventory = mock(InventoryReservationService.class);
    private final PurchaseSagaStore sagas = mock(PurchaseSagaStore.class);
    private final PurchaseResourceReservationTransaction reservation =
            new PurchaseResourceReservationTransaction(claims, inventory, sagas);

    @Test
    void reservesUnlimitedClaimAndFiniteInventoryBeforeAdvancingSaga() {
        UUID id = UUID.randomUUID();
        PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Unlimited", PlanType.UNLIMITED,
                8_000, null, true);

        reservation.reserve(id, "alice", plan);

        verify(claims).claim("alice", id);
        verify(inventory).reserveOne(plan.id(), id);
        verify(sagas).resourcesReserved(id);
    }

    @Test
    void translatesUnlimitedClaimUniquenessConflictToBusinessConflict() {
        UUID id = UUID.randomUUID();
        PlanPurchaseDetails plan = new PlanPurchaseDetails(UUID.randomUUID(), "Unlimited", PlanType.UNLIMITED,
                8_000, null, false);
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate claim"))
                .when(claims).claim("alice", id);

        assertThatThrownBy(() -> reservation.reserve(id, "alice", plan))
                .isInstanceOf(MembershipException.class)
                .satisfies(failure -> {
                    MembershipException exception = (MembershipException) failure;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo(ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE);
                });
        verify(sagas, never()).resourcesReserved(id);
    }

}
