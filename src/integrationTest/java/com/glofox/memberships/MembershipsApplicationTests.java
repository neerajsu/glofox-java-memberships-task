package com.glofox.memberships;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static com.glofox.memberships.testharness.constant.Constants.TEST_HARNESS_SETTINGS_PATH;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.testharness.service.FaultInjectionAspect;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MembershipsApplicationTests {
	@Autowired MockMvc mockMvc;
	@Autowired ApplicationContext context;

	@Test
	void contextLoads() {
		org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(TestHarnessSettingsService.class)).isEmpty();
		org.assertj.core.api.Assertions.assertThat(context.getBeansOfType(FaultInjectionAspect.class)).isEmpty();
	}

	@Test
	void testHarnessAdminApiIsAbsentWhenDisabled() throws Exception {
		mockMvc.perform(get(TEST_HARNESS_SETTINGS_PATH)).andExpect(status().isNotFound());
	}
}
