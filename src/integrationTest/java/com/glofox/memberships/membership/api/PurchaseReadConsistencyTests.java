package com.glofox.memberships.membership.api;

import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.service.purchase.PlanPurchaseDetails;
import com.glofox.memberships.membership.service.purchase.PurchaseFinalizer;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import com.glofox.memberships.plan.model.PlanType;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PurchaseReadConsistencyTests.BlockingGatewayConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseReadConsistencyTests {

    @Autowired MockMvc mvc;
    @Autowired BlockingGateway gateway;
    @Autowired CacheManager caches;
    @Autowired MembershipPurchaseRepository purchases;
    @Autowired PurchaseSagaRepository sagas;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired IdempotencyRecordRepository idempotency;
    @Autowired PurchaseFinalizer finalizer;
    @Autowired PurchaseReadGate readGate;

    @BeforeEach
    void reset() {
        purchases.deleteAll();
        attempts.deleteAll();
        sagas.deleteAll();
        idempotency.deleteAll();
        caches.getCacheNames().forEach(name -> caches.getCache(name).clear());
        gateway.reset();
        readGate.reset();
    }

    @AfterEach
    void releaseGateway() {
        gateway.release.countDown();
        readGate.release.countDown();
    }

    @Test
    void getDuringPaymentCannotMakeSubsequentPurchaseReadsStale() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> post = executor.submit(() -> mvc.perform(post(PURCHASE_COLLECTION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, "cache-race-" + UUID.randomUUID())
                    .content("{\"memberName\":\"Alice\",\"planId\":\"" + SeedData.TEN_CLASS_PACK_ID
                            + "\",\"startDate\":\"2026-11-02\"}"))
                    .andReturn());

            org.assertj.core.api.Assertions.assertThat(gateway.entered.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "0").param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements", is(0)));

            gateway.release.countDown();
            post.get(5, TimeUnit.SECONDS);

            mvc.perform(get(PURCHASE_COLLECTION_PATH).param("page", "0").param("limit", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements", is(1)))
                    .andExpect(jsonPath("$.content[0].memberName", is("Alice")));
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedFinalizationLeavesNoPurchaseVisibleToSubsequentReads() throws Exception {
        mvc.perform(get(PURCHASE_COLLECTION_PATH)).andExpect(jsonPath("$.totalElements", is(0)));
        var request = new PurchaseRequest("Alice", UUID.randomUUID(), java.time.LocalDate.of(2026, 11, 2));
        var plan = new PlanPurchaseDetails(request.planId(), "Pack", PlanType.PACK, 5_000, 10, true);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> finalizer.finalizePurchase(
                UUID.randomUUID(), request, "alice", plan, "txn", "missing-idempotency-key"))
                .isInstanceOf(IllegalStateException.class);
        mvc.perform(get(PURCHASE_COLLECTION_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
        org.assertj.core.api.Assertions.assertThat(purchases.count()).isZero();
    }

    @Test
    void readThatFinishesAfterCommitCannotPublishAStalePageForSubsequentRequests() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        readGate.armed.set(true);
        try {
            var oldRead = executor.submit(() -> mvc.perform(get(PURCHASE_COLLECTION_PATH)).andReturn());
            org.assertj.core.api.Assertions.assertThat(readGate.fetched.await(5, TimeUnit.SECONDS)).isTrue();
            gateway.release.countDown();
            mvc.perform(post(PURCHASE_COLLECTION_PATH).contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                    .content("{\"memberName\":\"Late read test\",\"planId\":\"" + SeedData.TEN_CLASS_PACK_ID
                            + "\",\"startDate\":\"2026-11-02\"}"))
                    .andExpect(status().isCreated());
            readGate.release.countDown();
            org.assertj.core.api.Assertions.assertThat(oldRead.get(5, TimeUnit.SECONDS).getResponse()
                    .getContentAsString()).contains("\"totalElements\":0");
            mvc.perform(get(PURCHASE_COLLECTION_PATH)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements", is(1)));
        } finally {
            readGate.release.countDown();
            gateway.release.countDown();
            executor.shutdownNow();
        }
    }

    @TestConfiguration
    static class BlockingGatewayConfiguration {
        @Bean @Primary BlockingGateway blockingGateway() { return new BlockingGateway(); }
        @Bean PurchaseReadGate purchaseReadGate() { return new PurchaseReadGate(); }
    }

    @Aspect
    static class PurchaseReadGate {
        final AtomicBoolean armed = new AtomicBoolean();
        volatile CountDownLatch fetched = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            armed.set(false);
            fetched = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Around("bean(membershipPurchaseRepository) && execution(* findAll(org.springframework.data.domain.Pageable))")
        public Object holdFetchedPage(ProceedingJoinPoint invocation) throws Throwable {
            Object page = invocation.proceed();
            if (armed.compareAndSet(true, false)) {
                fetched.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Read gate timed out");
            }
            return page;
        }
    }

    static class BlockingGateway implements PaymentGateway {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        @Override
        public PaymentResult charge(PaymentCommand command) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test payment gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new PaymentResult("cache-race-transaction");
        }

        void reset() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }
}
