package com.glofox.memberships;

import static com.glofox.memberships.common.logging.LogEvents.APPLICATION_SEED_COMPLETED;
import static com.glofox.memberships.common.logging.LogEvents.APPLICATION_SEED_PLAN;

import com.glofox.memberships.config.SeedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@Slf4j
public class MembershipsApplication {

	public static void main(String[] args) {
		SpringApplication.run(MembershipsApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	void logSeededPlans() {
		log.info("event={} planCount={}", APPLICATION_SEED_COMPLETED, SeedData.PLANS.size());
		SeedData.PLANS.forEach(plan -> log.debug(
				"event={} planId={} planName={} planType={} priceCents={} credits={} remainingSlots={}",
				APPLICATION_SEED_PLAN, plan.id(), plan.name(), plan.type(), plan.priceCents(), plan.credits(), plan.remainingSlots()));
	}
}
