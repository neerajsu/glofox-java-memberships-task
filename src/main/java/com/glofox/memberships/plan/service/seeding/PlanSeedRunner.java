package com.glofox.memberships.plan.service.seeding;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanSeedRunner implements ApplicationRunner {

    private final PlanSeeder planSeeder;

    @Override
    public void run(ApplicationArguments args) {
        planSeeder.seed();
    }
}
