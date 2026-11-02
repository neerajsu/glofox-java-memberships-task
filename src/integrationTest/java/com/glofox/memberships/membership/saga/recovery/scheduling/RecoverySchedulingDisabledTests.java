package com.glofox.memberships.membership.saga.recovery.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RecoverySchedulingDisabledTests {
    @Autowired ApplicationContext context;

    @Test
    void disabledSchedulerAndLockProviderAreAbsent() {
        assertThat(context.getBeansOfType(PurchaseRecoveryScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(LockProvider.class)).isEmpty();
    }
}
