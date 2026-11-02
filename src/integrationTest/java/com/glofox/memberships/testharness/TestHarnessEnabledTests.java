package com.glofox.memberships.testharness;

import static com.glofox.memberships.common.constant.Constants.API_JSON_CONTENT_TYPE;
import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.inventory.constant.Constants.INVENTORY_TEMPORARILY_UNAVAILABLE_CODE;
import static com.glofox.memberships.membership.constant.Constants.COMPLETED_STATUS;
import static com.glofox.memberships.membership.constant.Constants.PAYMENT_OUTCOME_UNKNOWN_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.testharness.constant.Constants.ACTIVATION_ERROR_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_SETTINGS_PATH;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_BEHAVIOR_SETTING;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static com.glofox.memberships.testharness.constant.Constants.INVALID_SETTING_VALUE_CODE;
import static com.glofox.memberships.testharness.constant.Constants.INVENTORY_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.MEMBERSHIP_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.NORMAL_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_TRANSIENT_ERROR_COUNT_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.UNAVAILABLE_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.UNKNOWN_SETTING_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.membership.repository.MembershipPurchaseRepository;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.membership.saga.persistence.PurchaseSagaStore;
import com.glofox.memberships.membership.saga.recovery.PurchaseSagaRecoveryService;
import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import com.glofox.memberships.testharness.repository.TestHarnessSettingRepository;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.test-harness.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestHarnessEnabledTests {
    @Autowired MockMvc mvc;
    @Autowired MembershipPurchaseRepository purchases;
    @Autowired PurchaseSagaRepository sagas;
    @Autowired PurchaseSagaStore sagaStore;
    @Autowired PurchaseSagaRecoveryService recovery;
    @Autowired SimulatedPaymentChargeRepository charges;
    @Autowired TestHarnessSettingRepository settingRepository;
    @Autowired TestHarnessSettingsService settings;

    @Test
    void providerFaultModesStillDistinguishDeclinePermanentTransientAndUnknownWithoutCharging() throws Exception {
        record Scenario(String behavior, int status, String code) {}
        var scenarios = java.util.List.of(
                new Scenario(com.glofox.memberships.testharness.constant.Constants.PAYMENT_DECLINED_SCENARIO, 402,
                        com.glofox.memberships.payment.constant.Constants.PAYMENT_DECLINED_CODE),
                new Scenario(com.glofox.memberships.testharness.constant.Constants.PAYMENT_PERMANENT_ERROR_SCENARIO, 502,
                        com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_ERROR_CODE),
                new Scenario(com.glofox.memberships.testharness.constant.Constants.TRANSIENT_ERROR_SCENARIO, 503,
                        com.glofox.memberships.payment.constant.Constants.PAYMENT_PROVIDER_UNAVAILABLE_CODE),
                new Scenario(com.glofox.memberships.testharness.constant.Constants.PAYMENT_TIMEOUT_BEFORE_PROCESSING_SCENARIO,
                        503, PAYMENT_OUTCOME_UNKNOWN_CODE));
        for (var scenario : scenarios) {
            set(com.glofox.memberships.testharness.constant.Constants.PAYMENT_BEHAVIOR_SETTING, scenario.behavior(), true);
            mvc.perform(post(PURCHASE_COLLECTION_PATH).contentType(API_JSON_CONTENT_TYPE)
                            .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()).content(request()))
                    .andExpect(status().is(scenario.status()))
                    .andExpect(jsonPath("$.code").value(scenario.code()));
            assertThat(charges.count()).isZero();
            assertThat(purchases.count()).isZero();
        }
    }

    @BeforeEach void clear() {
        purchases.deleteAll();
        sagas.deleteAll();
        charges.deleteAll();
        settingRepository.deleteAll();
    }

    @Test
    void internalV1SupportsListingReadingAndUpdatingSettings() throws Exception {
        mvc.perform(get(TEST_HARNESS_SETTINGS_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].description").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.containsString("Allowed values:"))));
        mvc.perform(get(TEST_HARNESS_SETTINGS_PATH + "/" + PAYMENT_BEHAVIOR_SETTING))
                .andExpect(status().isOk()).andExpect(jsonPath("$.key").value(PAYMENT_BEHAVIOR_SETTING))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.containsString(
                        PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO)));
        set(PAYMENT_BEHAVIOR_SETTING, NORMAL_SCENARIO, true);
        mvc.perform(get(TEST_HARNESS_SETTINGS_PATH + "/" + PAYMENT_BEHAVIOR_SETTING))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.containsString("Allowed values:")));
        mvc.perform(get("/internal/admin/test-harness/settings")).andExpect(status().isNotFound());
    }

    @Test
    void timeoutAfterProcessingCommitsLedgerAndRecoveryReplaysSameCharge() throws Exception {
        mvc.perform(put(TEST_HARNESS_SETTINGS_PATH + "/" + PAYMENT_BEHAVIOR_SETTING)
                        .contentType(API_JSON_CONTENT_TYPE).content("""
                                {"value":"%s","enabled":true}
                                """.formatted(PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO)))
                .andExpect(status().isOk());
        String key = UUID.randomUUID().toString();
        mvc.perform(post(PURCHASE_COLLECTION_PATH).contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(request()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(PAYMENT_OUTCOME_UNKNOWN_CODE));

        var saga = sagas.findAll().getFirst();
        assertThat(charges.count()).isEqualTo(1);
        sagaStore.deferRecovery(saga.getId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);

        assertThat(purchases.count()).isEqualTo(1);
        assertThat(charges.count()).isEqualTo(1);
        assertThat(sagas.findById(saga.getId()).orElseThrow().getStatus().name()).isEqualTo(COMPLETED_STATUS);
    }

    @Test
    void rejectsUnknownSettingAndUnsafeValue() throws Exception {
        mvc.perform(put(TEST_HARNESS_SETTINGS_PATH + "/unknown.setting")
                        .contentType(API_JSON_CONTENT_TYPE).content("{\"value\":\"x\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UNKNOWN_SETTING_CODE));
        mvc.perform(put(TEST_HARNESS_SETTINGS_PATH + "/" + PAYMENT_BEHAVIOR_SETTING)
                        .contentType(API_JSON_CONTENT_TYPE).content("{\"value\":\"exec()\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(INVALID_SETTING_VALUE_CODE));
    }

    @Test
    void oneShotCountersAreConsumedAtomically() throws Exception {
        mvc.perform(put(TEST_HARNESS_SETTINGS_PATH + "/" + PAYMENT_TRANSIENT_ERROR_COUNT_SETTING)
                        .contentType(API_JSON_CONTENT_TYPE).content("{\"value\":\"2\",\"enabled\":true}"))
                .andExpect(status().isOk());
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> executor.submit(() -> settings.consumeCounter(PAYMENT_TRANSIENT_ERROR_COUNT_SETTING)))
                    .toList();
            long consumed = 0;
            for (var attempt : attempts) if (attempt.get()) consumed++;
            assertThat(consumed).isEqualTo(2);
        } catch (Exception e) {
            throw new AssertionError("Counter consumption failed", e);
        }
    }

    @Test
    void inventoryFaultIsOneShotAndDoesNotCreateReservation() throws Exception {
        set(INVENTORY_BEHAVIOR_SETTING, UNAVAILABLE_SCENARIO, true);
        mvc.perform(post(PURCHASE_COLLECTION_PATH).contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .content(requestFor(SeedData.LAUNCH_PROMO_PACK_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(INVENTORY_TEMPORARILY_UNAVAILABLE_CODE));
        assertThat(purchases.count()).isZero();
    }

    @Test
    void membershipActivationFailureLeavesConfirmedPaymentForRecovery() throws Exception {
        set(MEMBERSHIP_BEHAVIOR_SETTING, ACTIVATION_ERROR_SCENARIO, true);
        String key = UUID.randomUUID().toString();
        mvc.perform(post(PURCHASE_COLLECTION_PATH).contentType(API_JSON_CONTENT_TYPE)
                        .header(IDEMPOTENCY_KEY_HEADER, key)
                        .content(requestFor(SeedData.MONTHLY_UNLIMITED_ID)))
                .andExpect(status().isInternalServerError());
        assertThat(purchases.count()).isZero();
        var saga = sagas.findAll().getFirst();
        assertThat(saga.getStatus().name()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(charges.count()).isEqualTo(1);
        String originalTransaction = charges.findAll().getFirst().getTransactionId();
        set(MEMBERSHIP_BEHAVIOR_SETTING, NORMAL_SCENARIO, true);
        sagaStore.deferRecovery(saga.getId(), Instant.now().minusSeconds(1));
        recovery.recoverPending(10);
        assertThat(purchases.count()).isEqualTo(1);
        assertThat(sagas.findById(saga.getId()).orElseThrow().getStatus().name()).isEqualTo(COMPLETED_STATUS);
        assertThat(charges.count()).isEqualTo(1);
        assertThat(charges.findAll().getFirst().getTransactionId()).isEqualTo(originalTransaction);
    }

    private String request() {
        return requestFor(SeedData.TEN_CLASS_PACK_ID);
    }
    private String requestFor(UUID planId) {
        return """
                {"memberName":"Harness User","planId":"%s","startDate":"2026-09-01"}
                """.formatted(planId);
    }
    private void set(String key, String value, boolean enabled) throws Exception {
        mvc.perform(put(TEST_HARNESS_SETTINGS_PATH + "/" + key)
                        .contentType(API_JSON_CONTENT_TYPE)
                        .content("{\"value\":\"" + value + "\",\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk());
    }
}
