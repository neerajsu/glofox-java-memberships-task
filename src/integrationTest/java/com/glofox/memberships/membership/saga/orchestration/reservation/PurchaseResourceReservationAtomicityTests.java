package com.glofox.memberships.membership.saga.orchestration.reservation;

import static com.glofox.memberships.config.SeedData.LAUNCH_PROMO_PACK_ID;
import static com.glofox.memberships.membership.constant.Constants.ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.glofox.memberships.common.exception.OptimisticLockRetryExhaustedException;
import com.glofox.memberships.inventory.exceptions.InventoryException;
import com.glofox.memberships.inventory.repository.InventoryReservationRepository;
import com.glofox.memberships.inventory.service.reservation.InventoryReservationService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.exceptions.MembershipException;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.repository.UnlimitedMembershipClaimRepository;
import com.glofox.memberships.membership.repository.entity.PurchaseSaga;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.plan.model.PlanType;
import com.glofox.memberships.plan.repository.PlanInventoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseResourceReservationAtomicityTests {
    @Autowired PurchaseResourceReservation reservation;
    @MockitoSpyBean PurchaseSagaStore sagas;
    @MockitoSpyBean InventoryReservationService inventory;
    @Autowired UnlimitedMembershipClaimRepository claims;
    @Autowired InventoryReservationRepository reservations;
    @Autowired PlanInventoryRepository inventories;
    @Autowired PurchaseSagaRepository sagaRepository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @PersistenceContext EntityManager entityManager;

    // A finite unlimited snapshot deliberately exercises both resource branches together.
    private final PlanPurchaseDetails plan = new PlanPurchaseDetails(LAUNCH_PROMO_PACK_ID,
            "Finite unlimited test snapshot", PlanType.UNLIMITED, 2500, null, true);

    @BeforeEach
    void reset() {
        claims.deleteAll();
        reservations.deleteAll();
        sagaRepository.deleteAll();
        jdbc.update("update plan_inventory set available_slots = 5, reserved_slots = 0 where plan_id = ?",
                LAUNCH_PROMO_PACK_ID);
    }

    @Test
    void inventoryExhaustionRollsBackClaimAndDoesNotRetryBusinessFailure() {
        UUID id = createOperation("exhausted");
        jdbc.update("update plan_inventory set available_slots = 0 where plan_id = ?", LAUNCH_PROMO_PACK_ID);
        assertThatThrownBy(() -> reservation.reserve(id, "exhausted", plan)).isInstanceOf(InventoryException.class);
        assertNoResources(id, 0);
        verify(inventory, times(1)).reserveOne(plan.id(), id);
        verify(sagas, never()).resourcesReserved(id);
    }

    @Test
    void failureAfterInventoryWriteRollsBackBothResourcesBeforeSagaTransition() {
        UUID id = createOperation("between-steps");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(claims.existsById("between-steps")).isTrue();
            assertThat(reservations.existsById(id)).isTrue();
            throw new IllegalStateException("Injected failure after resource writes");
        }).when(inventory).reserveOne(plan.id(), id);
        assertThatThrownBy(() -> reservation.reserve(id, "between-steps", plan))
                .isInstanceOf(IllegalStateException.class);
        assertNoResources(id, 5);
        verify(inventory, times(1)).reserveOne(plan.id(), id);
        verify(sagas, never()).resourcesReserved(id);
    }

    @Test
    void databaseConstraintDuringSagaTransitionRollsBackInventoryAndClaimWithoutMisclassification() {
        UUID id = createOperation("saga-failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            jdbc.update("update purchase_sagas set correlation_id = null where id = ?", id);
            return null;
        }).when(sagas).resourcesReserved(id);
        assertThatThrownBy(() -> reservation.reserve(id, "saga-failure", plan))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(MembershipException.class);
        assertNoResources(id, 5);
        verify(sagas, times(1)).resourcesReserved(id);
    }

    @Test
    void actualOptimisticConflictRollsBackResourcesAndReloadsSagaInFreshTransaction() {
        UUID id = createOperation("retry");
        List<EntityManager> attempts = introduceVersionConflicts(id, 1);
        reservation.reserve(id, "retry", plan);
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).isNotSameAs(attempts.get(1));
        assertReservedOnce(id);
        verify(sagas, times(2)).resourcesReserved(id);
    }

    @Test
    void actualOptimisticConflictsExhaustAfterThreeFreshTransactionsWithoutPartialResources() {
        UUID id = createOperation("exhaust-retry");
        List<EntityManager> attempts = introduceVersionConflicts(id, 3);
        assertThatThrownBy(() -> reservation.reserve(id, "exhaust-retry", plan))
                .isInstanceOf(OptimisticLockRetryExhaustedException.class);
        assertThat(attempts).hasSize(3).doesNotHaveDuplicates();
        assertNoResources(id, 5);
        verify(sagas, times(3)).resourcesReserved(id);
    }

    @Test
    void successfulReservationCommitsAllResourcesAndOneSagaTransitionTogether() {
        UUID id = createOperation("success");
        long version = sagaRepository.findById(id).orElseThrow().getVersion();
        reservation.reserve(id, "success", plan);
        assertReservedOnce(id);
        assertThat(sagaRepository.findById(id).orElseThrow().getVersion()).isEqualTo(version + 1);
        verify(sagas, times(1)).resourcesReserved(id);
    }

    @Test
    void duplicateUnlimitedClaimRemainsBusinessConflictAndDoesNotReserveAgain() {
        UUID winner = createOperation("same-member");
        reservation.reserve(winner, "same-member", plan);
        UUID loser = createOperation("same-member");
        assertThatThrownBy(() -> reservation.reserve(loser, "same-member", plan))
                .isInstanceOfSatisfying(MembershipException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(ACTIVE_UNLIMITED_MEMBERSHIP_EXISTS_CODE));
        assertReservedOnce(winner);
        assertThat(reservations.existsById(loser)).isFalse();
        assertThat(sagaRepository.findById(loser).orElseThrow().getStatus()).isEqualTo(PurchaseSagaStatus.VALIDATED);
        verify(inventory, never()).reserveOne(plan.id(), loser);
    }

    private List<EntityManager> introduceVersionConflicts(UUID id, int conflictCount) {
        List<EntityManager> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
            assertThat(holder).isNotNull();
            attempts.add(holder.getEntityManager());
            PurchaseSaga loaded = entityManager.find(PurchaseSaga.class, id);
            assertThat(loaded.getStatus()).isEqualTo(PurchaseSagaStatus.VALIDATED);
            if (attempts.size() <= conflictCount) {
                var concurrent = new TransactionTemplate(transactions);
                concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                concurrent.executeWithoutResult(status -> jdbc.update(
                        "update purchase_sagas set version = version + 1 where id = ?", id));
            }
            invocation.callRealMethod();
            entityManager.flush(); // Real stale-version UPDATE, not a mocked optimistic exception.
            return null;
        }).when(sagas).resourcesReserved(id);
        return attempts;
    }

    private UUID createOperation(String member) {
        UUID id = UUID.randomUUID();
        var request = new PurchaseRequest(member, plan.id(), LocalDate.of(2026, 11, 2));
        sagas.create(id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), request, member,
                plan, true, "reservation-atomicity-test");
        return id;
    }

    private void assertNoResources(UUID id, int available) {
        assertThat(claims.count()).isZero();
        assertThat(reservations.count()).isZero();
        var inventory = inventories.findById(plan.id()).orElseThrow();
        assertThat(inventory.getAvailableSlots()).isEqualTo(available);
        assertThat(inventory.getReservedSlots()).isZero();
        assertThat(sagaRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PurchaseSagaStatus.VALIDATED);
    }

    private void assertReservedOnce(UUID id) {
        assertThat(claims.count()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
        var inventory = inventories.findById(plan.id()).orElseThrow();
        assertThat(inventory.getAvailableSlots()).isEqualTo(4);
        assertThat(inventory.getReservedSlots()).isEqualTo(1);
        assertThat(sagaRepository.findById(id).orElseThrow().getStatus()).isEqualTo(PurchaseSagaStatus.RESOURCES_RESERVED);
    }
}
