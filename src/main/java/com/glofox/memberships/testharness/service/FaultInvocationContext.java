package com.glofox.memberships.testharness.service;

import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_FAULT_SCENARIO_CONTEXT;

import com.glofox.memberships.testharness.model.FaultScenario;

public final class FaultInvocationContext {
    private FaultInvocationContext() {}
    public static void set(FaultScenario value) { TEST_HARNESS_FAULT_SCENARIO_CONTEXT.set(value); }
    public static FaultScenario current() { return TEST_HARNESS_FAULT_SCENARIO_CONTEXT.get() == null ? FaultScenario.NORMAL : TEST_HARNESS_FAULT_SCENARIO_CONTEXT.get(); }
    public static void clear() { TEST_HARNESS_FAULT_SCENARIO_CONTEXT.remove(); }
}
