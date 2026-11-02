package com.glofox.memberships.testharness.service;

import static com.glofox.memberships.testharness.constant.Constants.ACTIVATION_ERROR_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.INVENTORY_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.INVENTORY_RESERVE_OPERATION;
import static com.glofox.memberships.testharness.constant.Constants.MEMBERSHIP_ACTIVATE_OPERATION;
import static com.glofox.memberships.testharness.constant.Constants.MEMBERSHIP_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_BEHAVIOR_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_CHARGE_OPERATION;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_DELAY_SETTING;
import static com.glofox.memberships.testharness.constant.Constants.TRANSIENT_ERROR_SCENARIO;
import static com.glofox.memberships.testharness.constant.Constants.UNAVAILABLE_SCENARIO;

import com.glofox.memberships.testharness.annotation.FaultInjectable;
import com.glofox.memberships.testharness.model.FaultScenario;
import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

@Aspect
@Component
@ConditionalOnProperty(prefix = "app.test-harness", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FaultInjectionAspect implements SmartInitializingSingleton {
    private final TestHarnessSettingsService settings;
    private final ListableBeanFactory beans;

    @Around("@annotation(fault)")
    public Object inject(ProceedingJoinPoint invocation, FaultInjectable fault) throws Throwable {
        if (!settings.runtimeEnabled()) return invocation.proceed();
        FaultScenario scenario = scenario(fault.value());
        if (scenario == FaultScenario.NORMAL) return invocation.proceed();
        Method targetMethod = AopUtils.getMostSpecificMethod(
                ((org.aspectj.lang.reflect.MethodSignature) invocation.getSignature()).getMethod(),
                AopUtils.getTargetClass(invocation.getTarget()));
        Method simulationMethod = targetMethod.getDeclaringClass().getMethod(fault.simulationMethod(), targetMethod.getParameterTypes());
        int delay = Integer.parseInt(settings.value(PAYMENT_DELAY_SETTING));
        if (delay > 0 && fault.value().equals(PAYMENT_CHARGE_OPERATION)) Thread.sleep(delay);
        FaultInvocationContext.set(scenario);
        try {
            return simulationMethod.invoke(invocation.getTarget(), invocation.getArgs());
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw ex.getCause();
        } finally {
            FaultInvocationContext.clear();
        }
    }

    private FaultScenario scenario(String operation) {
        if (operation.equals(PAYMENT_CHARGE_OPERATION)) {
            try { return FaultScenario.valueOf(settings.value(PAYMENT_BEHAVIOR_SETTING)); }
            catch (IllegalArgumentException ignored) { return FaultScenario.NORMAL; }
        }
        if (operation.equals(INVENTORY_RESERVE_OPERATION)) {
            return switch (settings.value(INVENTORY_BEHAVIOR_SETTING)) {
                case UNAVAILABLE_SCENARIO -> FaultScenario.INVENTORY_UNAVAILABLE;
                case TRANSIENT_ERROR_SCENARIO -> FaultScenario.INVENTORY_TRANSIENT_ERROR;
                default -> FaultScenario.NORMAL;
            };
        }
        if (operation.equals(MEMBERSHIP_ACTIVATE_OPERATION)) {
            return settings.value(MEMBERSHIP_BEHAVIOR_SETTING).equals(ACTIVATION_ERROR_SCENARIO)
                    ? FaultScenario.MEMBERSHIP_ACTIVATION_ERROR : FaultScenario.NORMAL;
        }
        return FaultScenario.NORMAL;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String name : beans.getBeanDefinitionNames()) {
            Class<?> type = beans.getType(name, false);
            if (type == null) continue;
            Class<?> target = AopUtils.getTargetClass(type);
            for (Method method : target.getMethods()) {
                FaultInjectable annotation = AnnotationUtils.findAnnotation(method, FaultInjectable.class);
                if (annotation == null || annotation.simulationMethod().isBlank()) continue;
                try {
                    Method simulationMethod = target.getMethod(annotation.simulationMethod(), method.getParameterTypes());
                    if (!method.getReturnType().isAssignableFrom(simulationMethod.getReturnType())) {
                        throw new IllegalStateException("Fault simulation return type is incompatible: " + target.getName() + "." + simulationMethod.getName());
                    }
                } catch (NoSuchMethodException ex) {
                    throw new IllegalStateException("Fault simulation method is missing for " + target.getName() + "." + method.getName(), ex);
                }
            }
        }
    }
}
