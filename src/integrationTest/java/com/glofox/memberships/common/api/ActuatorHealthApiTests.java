package com.glofox.memberships.common.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorHealthApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesOverallHealthAndProbeGroupsWithoutComponentDetails() throws Exception {
        for (String path : new String[] {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/livez",
            "/readyz"
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", is("UP")))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
    }
}
