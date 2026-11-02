package com.glofox.memberships.testharness.service;

import static com.glofox.memberships.testharness.constant.Constants.INVALID_SETTING_VALUE_CODE;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_DECLINED_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_DELAY_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_TRANSIENT_ERROR_COUNT_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_ENABLED_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_SETTING_DEFINITIONS;
import static com.glofox.memberships.testharness.constant.Constants.UNKNOWN_SETTING_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.testharness.exceptions.TestHarnessSettingException;
import com.glofox.memberships.testharness.repository.TestHarnessSettingRepository;
import com.glofox.memberships.testharness.repository.entity.TestHarnessSetting;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TestHarnessSettingsServiceTests {

    private final TestHarnessSettingRepository repository = mock(TestHarnessSettingRepository.class);
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final TestHarnessSettingsService service = new TestHarnessSettingsService(repository, retry);

    @Test
    void everyDefaultSettingDescribesItsAcceptedValuesOrInclusiveRange() {
        assertThat(service.all()).allSatisfy(setting -> assertThat(setting.description()).contains("Allowed values:"));
        assertThat(service.get(TEST_HARNESS_ENABLED_SETTING).description()).contains("true, false");
        assertThat(service.get(PAYMENT_BEHAVIOR_SETTING).description()).contains(
                com.glofox.memberships.testharness.constant.Constants.NORMAL_SCENARIO,
                com.glofox.memberships.testharness.constant.Constants.PAYMENT_SUCCESS_SCENARIO,
                PAYMENT_DECLINED_SCENARIO,
                com.glofox.memberships.testharness.constant.Constants.TRANSIENT_ERROR_SCENARIO,
                com.glofox.memberships.testharness.constant.Constants.PAYMENT_PERMANENT_ERROR_SCENARIO,
                com.glofox.memberships.testharness.constant.Constants.PAYMENT_TIMEOUT_BEFORE_PROCESSING_SCENARIO,
                com.glofox.memberships.testharness.constant.Constants.PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO);
        assertThat(service.get(PAYMENT_DELAY_SETTING).description()).contains("integer from 0 to 10000 (inclusive)");
        assertThat(service.get(PAYMENT_TRANSIENT_ERROR_COUNT_SETTING).description()).contains("integer from 0 to 10 (inclusive)");
    }

    @Test
    void oldPersistedDescriptionsDoNotHideCurrentAllowedValuesOnReadOrUpdate() {
        var existing = new TestHarnessSetting(PAYMENT_BEHAVIOR_SETTING, PAYMENT_DECLINED_SCENARIO,
                "Old description without possible values", true, "operator");
        when(repository.findByKey(PAYMENT_BEHAVIOR_SETTING)).thenReturn(Optional.of(existing));
        String expectedDescription = TEST_HARNESS_SETTING_DEFINITIONS.get(PAYMENT_BEHAVIOR_SETTING).description();
        assertThat(expectedDescription).contains("Allowed values:", PAYMENT_DECLINED_SCENARIO)
                .doesNotContain("Old description");
        assertThat(service.all()).filteredOn(setting -> setting.key().equals(PAYMENT_BEHAVIOR_SETTING))
                .singleElement().satisfies(setting -> assertThat(setting.description()).isEqualTo(expectedDescription));
        when(repository.saveAndFlush(existing)).thenReturn(existing);
        when(retry.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<Object> operation = invocation.getArgument(0);
            return operation.get();
        });
        assertThat(service.update(PAYMENT_BEHAVIOR_SETTING, PAYMENT_DECLINED_SCENARIO, true, "operator").description())
                .isEqualTo(expectedDescription);
    }

    @Test
    void normalizesAndPersistsAllowedScenario() {
        when(repository.findByKey(PAYMENT_BEHAVIOR_SETTING)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(TestHarnessSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(retry.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Supplier<Object> operation = invocation.getArgument(0);
            return operation.get();
        });

        var result = service.update(PAYMENT_BEHAVIOR_SETTING, " declined ", true, "operator");

        assertThat(result.value()).isEqualTo(PAYMENT_DECLINED_SCENARIO);
        assertThat(result.enabled()).isTrue();
        assertThat(result.updatedBy()).isEqualTo("operator");
    }

    @Test
    void rejectsUnknownKeysAndValuesOutsideTheAllowList() {
        assertThatThrownBy(() -> service.update("unknown.setting", "anything", true, "operator"))
                .isInstanceOf(TestHarnessSettingException.class)
                .satisfies(failure -> assertThat(((TestHarnessSettingException) failure).getCode())
                        .isEqualTo(UNKNOWN_SETTING_CODE));
        assertThatThrownBy(() -> service.update(PAYMENT_BEHAVIOR_SETTING, "execute-command", true, "operator"))
                .isInstanceOf(TestHarnessSettingException.class)
                .satisfies(failure -> assertThat(((TestHarnessSettingException) failure).getCode())
                        .isEqualTo(INVALID_SETTING_VALUE_CODE));
    }
}
