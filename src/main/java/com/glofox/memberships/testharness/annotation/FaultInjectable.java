package com.glofox.memberships.testharness.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * See the docs/05-services-and-components.md#faultinjectable-simulation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FaultInjectable {
    String value();

    String simulationMethod() default "";
}
