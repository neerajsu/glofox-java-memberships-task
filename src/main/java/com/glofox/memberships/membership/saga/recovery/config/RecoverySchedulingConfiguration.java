package com.glofox.memberships.membership.saga.recovery.config;

import static com.glofox.memberships.membership.constant.Constants.RECOVERY_PROPERTY_PREFIX;
import static com.glofox.memberships.membership.constant.Constants.RECOVERY_LOCK_DURATION;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = RECOVERY_PROPERTY_PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = RECOVERY_LOCK_DURATION)
public class RecoverySchedulingConfiguration {
    @Bean
    public LockProvider recoveryLockProvider(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(1);
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc).usingDbTime().build());
    }
}
