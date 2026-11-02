package com.glofox.memberships.membership.saga.recovery.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentResult;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class RecoveryPaymentGatewayTests {
    @Test
    void timeoutIsUnknownAndAnUncooperativeProviderCannotGrowTheWorkerQueue() {
        RecoveryProperties properties = new RecoveryProperties();
        properties.setProviderTimeout(Duration.ofMillis(30));
        CountDownLatch release = new CountDownLatch(1);
        PaymentGateway provider = command -> {
            while (release.getCount() > 0) {
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulate a client that ignores cancellation. */ }
            }
            return new PaymentResult("late-transaction");
        };
        RecoveryPaymentGateway gateway = new RecoveryPaymentGateway(provider, properties);
        try {
            assertThatThrownBy(() -> gateway.charge(null)).isInstanceOfSatisfying(PaymentProviderException.class,
                    failure -> assertThat(failure.getFailureType()).isEqualTo(FailureType.UNKNOWN));
            assertThatThrownBy(() -> gateway.charge(null)).isInstanceOfSatisfying(PaymentProviderException.class,
                    failure -> assertThat(failure.getFailureType()).isEqualTo(FailureType.TRANSIENT));
        } finally {
            release.countDown();
            gateway.close();
        }
    }
}
