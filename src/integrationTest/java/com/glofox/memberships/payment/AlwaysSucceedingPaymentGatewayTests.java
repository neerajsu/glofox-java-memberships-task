package com.glofox.memberships.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glofox.memberships.payment.exceptions.PaymentAmountMismatchException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import com.glofox.memberships.testharness.service.FaultInjectionAspect;
import com.glofox.memberships.testharness.service.SimulatedPaymentLedger;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(AlwaysSucceedingPaymentGatewayTests.CollisionConfiguration.class)
class AlwaysSucceedingPaymentGatewayTests {
    @Autowired AlwaysSucceedingPaymentGateway gateway;
    @Autowired SimulatedPaymentLedger ledger;
    @Autowired SimulatedPaymentChargeRepository charges;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired ReadCollisionGate collisionGate;

    @Test
    void normalModeReplaysSameKeyAndDifferentKeysProduceDifferentTransactions() {
        String key = UUID.randomUUID().toString();
        var first = gateway.charge(command(key, 5000));
        var second = gateway.charge(command(key, 5000));
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(gateway.charge(command(UUID.randomUUID().toString(), 5000)).transactionId())
                .isNotEqualTo(first.transactionId());
        assertThat(charges.findAll().stream().filter(charge -> charge.getProviderKey().equals(key))).hasSize(1);
        assertThat(context.getBeansOfType(TestHarnessSettingsService.class)).isEmpty();
        assertThat(context.getBeansOfType(FaultInjectionAspect.class)).isEmpty();
    }

    @Test
    void amountMismatchIsRejectedWithoutChangingTheExistingChargeOrClaimingADecline() {
        String key = UUID.randomUUID().toString();
        var original = gateway.charge(command(key, 5000));
        assertThatThrownBy(() -> ledger.recordOrReplay(key, 6000)).isInstanceOf(PaymentAmountMismatchException.class);
        assertThatThrownBy(() -> gateway.charge(command(key, 6000)))
                .isInstanceOfSatisfying(PaymentProviderException.class,
                        failure -> assertThat(failure.getFailureType()).isEqualTo(FailureType.UNKNOWN));
        var persisted = charges.findByProviderKey(key).orElseThrow();
        assertThat(persisted.getAmountCents()).isEqualTo(5000);
        assertThat(persisted.getTransactionId()).isEqualTo(original.transactionId());
    }

    @Test
    void concurrentEmptyReadsCollideOnTheUniqueKeyThenReloadTheWinnerInValidTransactions() throws Exception {
        int workers = 6;
        String key = UUID.randomUUID().toString();
        collisionGate.arm(key, workers);
        var executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<PaymentResult>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) results.add(executor.submit(() -> gateway.charge(command(key, 5000))));
            List<String> ids = new ArrayList<>();
            for (var result : results) ids.add(result.get(10, TimeUnit.SECONDS).transactionId());
            assertThat(ids).hasSize(workers).containsOnly(ids.getFirst());
            assertThat(collisionGate.remaining.get()).isLessThanOrEqualTo(0);
            assertThat(charges.findAll().stream().filter(charge -> charge.getProviderKey().equals(key))).hasSize(1);
        } finally {
            collisionGate.disarm();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void committedProviderChargeSurvivesOuterPurchaseTransactionRollback() {
        String key = UUID.randomUUID().toString();
        var rollback = new IllegalStateException("controlled outer rollback");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            gateway.charge(command(key, 5000));
            throw rollback;
        })).isSameAs(rollback);
        var persisted = charges.findByProviderKey(key).orElseThrow();
        assertThat(gateway.charge(command(key, 5000)).transactionId()).isEqualTo(persisted.getTransactionId());
    }

    @Test
    void actualH2SchemaHasAUniqueProviderKeyIndex() throws Exception {
        boolean uniqueProviderKey = false;
        try (var connection = dataSource.getConnection();
                var indexes = connection.getMetaData().getIndexInfo(
                        null, "PUBLIC", "TEST_HARNESS_PAYMENT_CHARGES", true, false)) {
            while (indexes.next()) {
                if ("provider_key".equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) uniqueProviderKey = true;
            }
        }
        assertThat(uniqueProviderKey).isTrue();
    }

    private PaymentCommand command(String key, int amount) {
        return new PaymentCommand(UUID.randomUUID(), key, amount, "Ledger Test", UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CollisionConfiguration {
        @Bean ReadCollisionGate readCollisionGate() { return new ReadCollisionGate(); }
    }

    /** Forces every initial SELECT to see no row before allowing any INSERT. No production test hooks. */
    @Aspect
    static class ReadCollisionGate {
        private volatile String key;
        private volatile CyclicBarrier barrier;
        private final AtomicInteger remaining = new AtomicInteger();

        void arm(String key, int parties) {
            barrier = new CyclicBarrier(parties);
            remaining.set(parties);
            this.key = key;
        }

        void disarm() { key = null; }

        @Around("execution(* com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository.findByProviderKey(..))")
        public Object afterRead(ProceedingJoinPoint invocation) throws Throwable {
            Object result = invocation.proceed();
            if (invocation.getArgs()[0].equals(key) && remaining.getAndDecrement() > 0) {
                assertThat((java.util.Optional<?>) result).isEmpty();
                barrier.await(5, TimeUnit.SECONDS);
            }
            return result;
        }
    }
}
