package com.glofox.memberships.membership.saga.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.service.purchase.PurchaseInitializationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.saga.recovery.minimum-age=PT30S")
@ActiveProfiles("test")
class RecoveryClaimTests {
    @Autowired PurchaseInitializationService initialization;
    @Autowired PurchaseSagaStore sagas;
    @Autowired JdbcTemplate jdbc;

    @Test
    void ageGateAndPerSagaLeaseProtectManualRecoveryAndExpiredClaimCanBeReclaimed() {
        var operation = initialization.initialize(UUID.randomUUID().toString(),
                new PurchaseRequest("Claim Test", SeedData.TEN_CLASS_PACK_ID, LocalDate.of(2026, 11, 2))).operation();
        Instant now = Instant.now();
        jdbc.update("update purchase_sagas set next_recovery_at = ? where id = ?",
                Timestamp.from(now.minusSeconds(1)), operation.operationId());
        var fresh = sagas.findById(operation.operationId());
        assertThat(sagas.findRecoverable(now, 10)).noneMatch(saga -> saga.getId().equals(operation.operationId()));
        assertThat(sagas.claimForRecovery(fresh, now, now.plusSeconds(10))).isFalse();

        jdbc.update("update purchase_sagas set updated_at = ? where id = ?",
                Timestamp.from(now.minusSeconds(31)), operation.operationId());
        var candidate = sagas.findById(operation.operationId());
        assertThat(sagas.findRecoverable(now, 10)).anyMatch(saga -> saga.getId().equals(operation.operationId()));
        assertThat(sagas.claimForRecovery(candidate, now, now.plusSeconds(10))).isTrue();
        assertThat(sagas.claimForRecovery(candidate, now, now.plusSeconds(10))).isFalse();
        var leased = sagas.findById(operation.operationId());
        assertThat(sagas.claimForRecovery(leased, now, now.plusSeconds(10))).isFalse();

        Instant afterCrashLease = now.plusSeconds(11);
        assertThat(sagas.findRecoverable(afterCrashLease, 10))
                .anyMatch(saga -> saga.getId().equals(operation.operationId()));
        assertThat(sagas.claimForRecovery(leased, afterCrashLease, afterCrashLease.plusSeconds(10))).isTrue();
    }
}
