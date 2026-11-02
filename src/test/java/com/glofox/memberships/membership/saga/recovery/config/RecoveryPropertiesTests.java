package com.glofox.memberships.membership.saga.recovery.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecoveryPropertiesTests {
    @Test
    void unsafePropertiesFailConfigurationBinding() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(RecoveryConfiguration.class)
                .withPropertyValues("app.saga.recovery.lock-at-most-for=PT10S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultsHaveMarginAndUnsafeTimeoutCombinationsAreRejected() {
        RecoveryProperties properties = new RecoveryProperties();
        assertThat(properties.isTimingSafe()).isTrue();
        assertThat(properties.isCronValid()).isTrue();
        properties.setLockAtMostFor(Duration.ofSeconds(30));
        assertThat(properties.isTimingSafe()).isFalse();
        properties.setCron("* * * * *");
        assertThat(properties.isCronValid()).isFalse();
    }
}
