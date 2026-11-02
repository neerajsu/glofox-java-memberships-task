package com.glofox.memberships.membership.saga.recovery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RecoveryProperties.class)
public class RecoveryConfiguration {}
