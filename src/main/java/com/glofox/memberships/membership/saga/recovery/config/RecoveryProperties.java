package com.glofox.memberships.membership.saga.recovery.config;

import static com.glofox.memberships.membership.constant.Constants.RECOVERY_PROPERTY_PREFIX;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(RECOVERY_PROPERTY_PREFIX)
public class RecoveryProperties {
    public static final Duration CLAIM_LEASE = Duration.ofMinutes(3);
    public static final Duration LOCAL_WORK_MARGIN = Duration.ofMinutes(1);
    private boolean enabled = true;
    private String cron = "*/15 * * * * *";
    @Min(1) @Max(10) private int batchSize = 10;
    @NotNull private Duration minimumAge = Duration.ofSeconds(90);
    @NotNull private Duration lockAtMostFor = Duration.ofMinutes(5);
    @NotNull private Duration providerTimeout = Duration.ofSeconds(5);
    @NotNull private Duration batchTimeout = Duration.ofMinutes(2);

    @AssertTrue(message = "Recovery cron must be a valid six-field Spring cron expression")
    public boolean isCronValid() { return cron != null && CronExpression.isValidExpression(cron); }

    @AssertTrue(message = "Enabled scheduling requires age >= 90s; timeouts must be positive, provider <= 5s, batch <= 2m, and lock >= batch + provider + 1m local-work margin")
    public boolean isTimingSafe() {
        return minimumAge != null && !minimumAge.isNegative()
                && (!enabled || minimumAge.compareTo(Duration.ofSeconds(90)) >= 0)
                && positive(providerTimeout) && providerTimeout.compareTo(Duration.ofSeconds(5)) <= 0
                && positive(batchTimeout) && batchTimeout.compareTo(Duration.ofMinutes(2)) <= 0
                && positive(lockAtMostFor)
                && lockAtMostFor.compareTo(batchTimeout.plus(providerTimeout).plus(LOCAL_WORK_MARGIN)) >= 0;
    }

    private boolean positive(Duration duration) { return duration != null && !duration.isNegative() && !duration.isZero(); }
}
