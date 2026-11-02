package com.glofox.memberships.membership.saga.orchestration.flow;

import static com.glofox.memberships.config.SeedData.LAUNCH_PROMO_PACK_ID;
import static com.glofox.memberships.config.SeedData.TEN_CLASS_PACK_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.membership.api.PurchaseRequest;
import com.glofox.memberships.membership.model.PurchaseSagaStatus;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.service.purchase.PurchaseService;
import com.glofox.memberships.payment.AlwaysSucceedingPaymentGateway;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseSagaFlowIsolationTests {
    @Autowired PurchaseService purchases;
    @Autowired MembershipPurchaseRepository repository;
    @Autowired PurchaseSagaRepository sagas;
    @Autowired IdempotencyRecordRepository records;
    @Autowired SimulatedPaymentChargeRepository charges;
    @MockitoSpyBean AlwaysSucceedingPaymentGateway gateway;

    @Test
    void simultaneousFlowsKeepTheirOwnContextCommandsAndResultsAndChargeOutsideTransactions() throws Exception {
        var barrier = new CyclicBarrier(2);
        List<PaymentCommand> commands = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            commands.add(invocation.getArgument(0));
            barrier.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(gateway).charge(any());
        String firstKey = UUID.randomUUID().toString();
        String secondKey = UUID.randomUUID().toString();
        var firstRequest = new PurchaseRequest("Concurrent Pack", TEN_CLASS_PACK_ID, LocalDate.of(2026, 11, 2));
        var secondRequest = new PurchaseRequest("Concurrent Promo", LAUNCH_PROMO_PACK_ID, LocalDate.of(2026, 12, 3));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> purchases.purchase(firstRequest, firstKey));
            var secondFuture = executor.submit(() -> purchases.purchase(secondRequest, secondKey));
            var first = firstFuture.get(10, TimeUnit.SECONDS);
            var second = secondFuture.get(10, TimeUnit.SECONDS);
            assertThat(first.id()).isNotEqualTo(second.id());
            assertThat(first.memberName()).isEqualTo(firstRequest.memberName());
            assertThat(first.planId()).isEqualTo(TEN_CLASS_PACK_ID);
            assertThat(first.priceCents()).isEqualTo(5000);
            assertThat(first.startDate()).isEqualTo(firstRequest.startDate());
            assertThat(second.memberName()).isEqualTo(secondRequest.memberName());
            assertThat(second.planId()).isEqualTo(LAUNCH_PROMO_PACK_ID);
            assertThat(second.priceCents()).isEqualTo(2500);
            assertThat(second.startDate()).isEqualTo(secondRequest.startDate());
            assertThat(first.paymentTransactionId()).isNotEqualTo(second.paymentTransactionId());
            assertThat(commands).hasSize(2);
            assertThat(commands).extracting(PaymentCommand::idempotencyKey).doesNotHaveDuplicates();
            assertThat(commands).anySatisfy(command -> {
                assertThat(command.purchaseAttemptId()).isEqualTo(first.id());
                assertThat(command.memberName()).isEqualTo(first.memberName());
                assertThat(command.amountCents()).isEqualTo(first.priceCents());
            }).anySatisfy(command -> {
                assertThat(command.purchaseAttemptId()).isEqualTo(second.id());
                assertThat(command.memberName()).isEqualTo(second.memberName());
                assertThat(command.amountCents()).isEqualTo(second.priceCents());
            });
            assertThat(repository.count()).isEqualTo(2);
            assertThat(charges.count()).isEqualTo(2);
            assertThat(sagas.findAll()).allSatisfy(saga -> assertThat(saga.getStatus()).isEqualTo(PurchaseSagaStatus.COMPLETED));
            assertThat(records.findById(firstKey).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(records.findById(secondKey).orElseThrow().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        }
    }
}
