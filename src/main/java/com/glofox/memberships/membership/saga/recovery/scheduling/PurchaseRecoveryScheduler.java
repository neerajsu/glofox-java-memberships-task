package com.glofox.memberships.membership.saga.recovery.scheduling;

import static com.glofox.memberships.membership.constant.Constants.RECOVERY_CRON;
import static com.glofox.memberships.membership.constant.Constants.RECOVERY_LOCK_DURATION;
import static com.glofox.memberships.membership.constant.Constants.RECOVERY_LOCK_NAME;
import static com.glofox.memberships.membership.constant.Constants.RECOVERY_PROPERTY_PREFIX;

import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryService;
import com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = RECOVERY_PROPERTY_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class PurchaseRecoveryScheduler {
    private final PurchaseSagaRecoveryService recovery;
    private final RecoveryProperties properties;

    @Scheduled(cron = RECOVERY_CRON, zone = "UTC")
    @SchedulerLock(name = RECOVERY_LOCK_NAME, lockAtMostFor = RECOVERY_LOCK_DURATION)
    public void recoverPendingPurchases() {
        LockAssert.assertLocked();
        try {
            recovery.recoverPending(properties.getBatchSize());
        } catch (RuntimeException failure) {
            log.error("event={} exceptionType={}",
                    com.glofox.memberships.common.logging.LogEvents.SAGA_RECOVERY_FAILED,
                    failure.getClass().getSimpleName(), failure);
        }
    }
}
