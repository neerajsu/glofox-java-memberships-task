package com.glofox.memberships.testharness.constant;

import com.glofox.memberships.testharness.model.FaultScenario;
import com.glofox.memberships.testharness.model.TestHarnessSettingDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/** Central names and protocol values for the testharness service. */
public final class Constants {
    private Constants() {}

    public static final String TEST_HARNESS_SETTINGS_PATH =
            com.glofox.memberships.common.constant.Constants.INTERNAL_API_V1_PATH + "/admin/test-harness/settings";
    public static final String ADMIN_IDENTITY_HEADER = "X-Admin-Identity";

    // Runtime settings.
    public static final String TEST_HARNESS_ENABLED_SETTING = "test-harness.enabled";
    public static final String PAYMENT_BEHAVIOR_SETTING = "payment.behavior";
    public static final String PAYMENT_DELAY_SETTING = "payment.delay-ms";
    public static final String PAYMENT_TRANSIENT_ERROR_COUNT_SETTING = "payment.transient-error-count";
    public static final String INVENTORY_BEHAVIOR_SETTING = "inventory.behavior";
    public static final String INVENTORY_FAILURE_COUNT_SETTING = "inventory.failure-count";
    public static final String MEMBERSHIP_BEHAVIOR_SETTING = "membership.behavior";
    public static final String MEMBERSHIP_FAILURE_COUNT_SETTING = "membership.failure-count";

    // AOP operation names.
    public static final String PAYMENT_CHARGE_OPERATION = "payment.charge";
    public static final String INVENTORY_RESERVE_OPERATION = "inventory.reserve";
    public static final String MEMBERSHIP_ACTIVATE_OPERATION = "membership.activate";

    // Supported simulated behaviors.
    public static final String NORMAL_SCENARIO = "NORMAL";
    public static final String UNAVAILABLE_SCENARIO = "UNAVAILABLE";
    public static final String TRANSIENT_ERROR_SCENARIO = "TRANSIENT_ERROR";
    public static final String PAYMENT_SUCCESS_SCENARIO = "SUCCESS";
    public static final String PAYMENT_DECLINED_SCENARIO = "DECLINED";
    public static final String PAYMENT_PERMANENT_ERROR_SCENARIO = "PERMANENT_ERROR";
    public static final String PAYMENT_TIMEOUT_BEFORE_PROCESSING_SCENARIO = "TIMEOUT_BEFORE_PROCESSING";
    public static final String PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO = "TIMEOUT_AFTER_PROCESSING";
    public static final String ACTIVATION_ERROR_SCENARIO = "ACTIVATION_ERROR";

    // API error codes.
    public static final String INVALID_SETTING_VALUE_CODE = "INVALID_TEST_HARNESS_VALUE";
    public static final String UNKNOWN_SETTING_CODE = "UNKNOWN_TEST_HARNESS_SETTING";

    /** Per-thread simulation state; use FaultInvocationContext to set, read and clear it. */
    public static final ThreadLocal<FaultScenario> TEST_HARNESS_FAULT_SCENARIO_CONTEXT = new ThreadLocal<>();

    // Immutable setting defaults, guidance and validation rules.
    public static final Map<String, TestHarnessSettingDefinition> TEST_HARNESS_SETTING_DEFINITIONS = createSettingDefinitions();

    private record ValueRule(String allowedValues, java.util.function.Predicate<String> valid) {}

    private static Map<String, TestHarnessSettingDefinition> createSettingDefinitions() {
        Map<String, TestHarnessSettingDefinition> m = new LinkedHashMap<>();
        add(m, TEST_HARNESS_ENABLED_SETTING, "true", "Runtime harness master switch", bool());
        add(m, PAYMENT_BEHAVIOR_SETTING, NORMAL_SCENARIO, "Payment provider behavior", oneOf(NORMAL_SCENARIO, PAYMENT_SUCCESS_SCENARIO, PAYMENT_DECLINED_SCENARIO, TRANSIENT_ERROR_SCENARIO, PAYMENT_PERMANENT_ERROR_SCENARIO, PAYMENT_TIMEOUT_BEFORE_PROCESSING_SCENARIO, PAYMENT_TIMEOUT_AFTER_PROCESSING_SCENARIO));
        add(m, PAYMENT_DELAY_SETTING, "0", "Payment simulation delay in milliseconds", integer(0, 10000));
        add(m, PAYMENT_TRANSIENT_ERROR_COUNT_SETTING, "0", "Number of payment transient errors to inject", integer(0, 10));
        add(m, INVENTORY_BEHAVIOR_SETTING, NORMAL_SCENARIO, "Inventory failure behavior", oneOf(NORMAL_SCENARIO, UNAVAILABLE_SCENARIO, TRANSIENT_ERROR_SCENARIO));
        add(m, INVENTORY_FAILURE_COUNT_SETTING, "0", "Inventory one-shot failures", integer(0, 10));
        add(m, MEMBERSHIP_BEHAVIOR_SETTING, NORMAL_SCENARIO, "Membership activation failure behavior", oneOf(NORMAL_SCENARIO, ACTIVATION_ERROR_SCENARIO));
        add(m, MEMBERSHIP_FAILURE_COUNT_SETTING, "0", "Membership one-shot failures", integer(0, 10));
        return Map.copyOf(m);
    }
    private static void add(Map<String, TestHarnessSettingDefinition> definitions, String key, String value, String description, ValueRule rule) {
        definitions.put(key, new TestHarnessSettingDefinition(value,
                description + ". Allowed values: " + rule.allowedValues() + ".", rule.valid()));
    }

    private static ValueRule bool() {
        return new ValueRule("true, false", value -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false"));
    }

    private static ValueRule oneOf(String... allowed) {
        return new ValueRule(String.join(", ", allowed), value -> java.util.Arrays.asList(allowed).contains(value));
    }

    private static ValueRule integer(int min, int max) {
        return new ValueRule("integer from " + min + " to " + max + " (inclusive)", value -> {
            try {
                int number = Integer.parseInt(value);
                return number >= min && number <= max;
            } catch (NumberFormatException invalidInteger) {
                return false;
            }
        });
    }
}
