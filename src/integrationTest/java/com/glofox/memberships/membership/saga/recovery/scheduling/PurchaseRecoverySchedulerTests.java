package com.glofox.memberships.membership.saga.recovery.scheduling;

import static com.glofox.memberships.membership.constant.Constants.RECOVERY_LOCK_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryService;
import com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"app.saga.recovery.enabled=true", "app.saga.recovery.cron=0 0 0 1 1 *",
        "app.saga.recovery.batch-size=3", "app.saga.recovery.minimum-age=PT90S"})
@ActiveProfiles("test")
class PurchaseRecoverySchedulerTests {
    @Autowired PurchaseRecoveryScheduler scheduler;
    @Autowired RecoveryProperties properties;
    @Autowired LockProvider provider;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PurchaseSagaRecoveryService recovery;

    @Test
    void competingProxiedInvocationsUseOneJdbcLockAndReleaseImmediately() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Recovery gate timed out");
            return 0;
        }).when(recovery).recoverPending(3);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(scheduler::recoverPendingPurchases);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.recoverPendingPurchases();
            verify(recovery, times(1)).recoverPending(3);
            assertThat(jdbc.queryForObject("select count(*) from shedlock where name = ?", Integer.class,
                    RECOVERY_LOCK_NAME)).isEqualTo(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            scheduler.recoverPendingPurchases();
            verify(recovery, times(2)).recoverPending(3);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void usesDatabaseTimeEvenWhenApplicationLockTimestampIsFarInTheFuture() {
        Instant artificialApplicationTime = Instant.now().plus(Duration.ofDays(10));
        var lock = provider.lock(new LockConfiguration(artificialApplicationTime, "db-time-test",
                Duration.ofSeconds(30), Duration.ZERO)).orElseThrow();
        try {
            Instant lockedAt = jdbc.queryForObject("select locked_at from shedlock where name = ?",
                    java.sql.Timestamp.class, "db-time-test").toInstant();
            assertThat(lockedAt).isBefore(artificialApplicationTime.minus(Duration.ofDays(1)));
            assertThat(lockedAt).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
        } finally { lock.unlock(); }
    }

    @Test
    void bindsCronBatchAndIsoDurations() {
        assertThat(properties.getCron()).isEqualTo("0 0 0 1 1 *");
        assertThat(properties.getBatchSize()).isEqualTo(3);
        assertThat(properties.getMinimumAge()).isEqualTo(Duration.ofSeconds(90));
        assertThat(properties.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getProviderTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getBatchTimeout()).isEqualTo(Duration.ofMinutes(2));
    }
}
