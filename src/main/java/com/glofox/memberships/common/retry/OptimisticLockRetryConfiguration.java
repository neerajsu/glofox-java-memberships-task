package com.glofox.memberships.common.retry;

import jakarta.persistence.OptimisticLockException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@Configuration
public class OptimisticLockRetryConfiguration {

    @Bean
    RetryTemplate optimisticLockRetryTemplate() {
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .includes(ObjectOptimisticLockingFailureException.class, OptimisticLockException.class)
                .maxRetries(2)
                .delay(Duration.ofMillis(20))
                .multiplier(2)
                .maxDelay(Duration.ofMillis(50))
                .jitter(Duration.ofMillis(5))
                .build();
        return new RetryTemplate(retryPolicy);
    }
}
