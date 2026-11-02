package com.glofox.memberships.config;

import static com.glofox.memberships.plan.constant.Constants.PLANS_CACHE;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfiguration {
    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(PLANS_CACHE);
    }
}
