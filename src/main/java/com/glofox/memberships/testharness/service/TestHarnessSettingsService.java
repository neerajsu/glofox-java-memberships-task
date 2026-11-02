package com.glofox.memberships.testharness.service;

import static com.glofox.memberships.testharness.constant.Constants.INVALID_SETTING_VALUE_CODE;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_ENABLED_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_SETTING_DEFINITIONS;
import static com.glofox.memberships.testharness.constant.Constants.UNKNOWN_SETTING_CODE;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.testharness.exceptions.TestHarnessSettingException;
import com.glofox.memberships.testharness.model.TestHarnessSettingDefinition;
import com.glofox.memberships.testharness.repository.TestHarnessSettingRepository;
import com.glofox.memberships.testharness.repository.entity.TestHarnessSetting;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "app.test-harness", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TestHarnessSettingsService {
    private final TestHarnessSettingRepository repository;
    private final OptimisticLockRetryPolicy optimisticLockRetryPolicy;

    public String value(String key) {
        TestHarnessSettingDefinition definition = definition(key);
        return repository.findByKey(key).filter(TestHarnessSetting::isEnabled)
                .map(TestHarnessSetting::getValue).orElse(definition.defaultValue());
    }

    public boolean runtimeEnabled() { return Boolean.parseBoolean(value(TEST_HARNESS_ENABLED_SETTING)); }

    @Transactional
    public boolean consumeCounter(String key) {
        if (!runtimeEnabled()) return false;
        return repository.decrementEnabledCounter(key) == 1;
    }
    public boolean counterConfigured(String key) {
        return repository.findByKey(key).map(TestHarnessSetting::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public List<SettingView> all() {
        return TEST_HARNESS_SETTING_DEFINITIONS.entrySet().stream().map(e -> view(e.getKey(), e.getValue())).toList();
    }

    @Transactional(readOnly = true)
    public SettingView get(String key) { return view(key, definition(key)); }

    public SettingView update(String key, String value, boolean enabled, String updatedBy) {
        TestHarnessSettingDefinition definition = definition(key);
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("false")
                ? normalized.toLowerCase(java.util.Locale.ROOT)
                : normalized.toUpperCase(java.util.Locale.ROOT);
        if (!definition.valid().test(normalized)) {
            throw new TestHarnessSettingException(HttpStatus.BAD_REQUEST, INVALID_SETTING_VALUE_CODE, "Value is not allowed for setting " + key);
        }
        String validatedValue = normalized;
        return optimisticLockRetryPolicy.execute(() -> {
            TestHarnessSetting setting = repository.findByKey(key).orElseGet(() ->
                    new TestHarnessSetting(key, definition.defaultValue(), definition.description(), false, null));
            setting.update(validatedValue, enabled, updatedBy);
            return toView(repository.saveAndFlush(setting));
        });
    }

    private SettingView view(String key, TestHarnessSettingDefinition definition) {
        TestHarnessSetting setting = repository.findByKey(key).orElse(null);
        return setting == null
                ? new SettingView(key, definition.defaultValue(), false, definition.description(), null, null)
                : toView(setting);
    }
    private SettingView toView(TestHarnessSetting s) {
        // Definitions own API guidance, including for rows saved before allowed values were documented.
        return new SettingView(s.getKey(), s.getValue(), s.isEnabled(), definition(s.getKey()).description(), s.getUpdatedAt(), s.getUpdatedBy());
    }
    private TestHarnessSettingDefinition definition(String key) {
        TestHarnessSettingDefinition d = TEST_HARNESS_SETTING_DEFINITIONS.get(key);
        if (d == null) throw new TestHarnessSettingException(HttpStatus.BAD_REQUEST, UNKNOWN_SETTING_CODE, "Unknown test harness setting");
        return d;
    }
    public record SettingView(String key, String value, boolean enabled, String description, java.time.Instant updatedAt, String updatedBy) {}
}
