package com.glofox.memberships.testharness.model;

import java.util.function.Predicate;

/** Immutable default, API guidance and validation rule for an allow-listed harness setting. */
public record TestHarnessSettingDefinition(String defaultValue, String description, Predicate<String> valid) {
}
