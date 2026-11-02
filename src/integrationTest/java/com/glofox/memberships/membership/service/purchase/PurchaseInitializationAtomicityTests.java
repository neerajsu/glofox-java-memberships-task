package com.glofox.memberships.membership.service.purchase;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.plan.model.PlanType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PurchaseInitializationAtomicityTests {

    @Autowired PurchaseInitializationTransaction transaction;
    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyRecordRepository records;
    @Autowired PurchaseSagaRepository sagas;

    @Test
    void rollsBackIdempotencyRecordWhenInitialSagaCannotBePersisted() {
        String key = "atomic-init-" + UUID.randomUUID();
        PurchaseRequest request = new PurchaseRequest("Alice", UUID.randomUUID(), LocalDate.of(2026, 11, 2));
        var prepared = idempotency.prepare(key, request);
        var plan = new PlanPurchaseDetails(UUID.randomUUID(), "P".repeat(121), PlanType.PACK,
                5_000, 10, false);
        String providerKey = "provider-" + UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transaction.createInProgress(
                prepared, UUID.randomUUID(), providerKey, request, "alice", plan, "cid"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(records.findById(key)).isEmpty();
        assertThat(sagas.findByClientIdempotencyKey(key)).isEmpty();

        var validPlan = new PlanPurchaseDetails(plan.id(), "Pack", PlanType.PACK, 5_000, 10, false);
        transaction.createInProgress(prepared, UUID.randomUUID(), providerKey, request, "alice", validPlan, "cid");
        assertThat(records.findById(key)).isPresent();
        assertThat(sagas.findByClientIdempotencyKey(key)).isPresent();
    }
}
