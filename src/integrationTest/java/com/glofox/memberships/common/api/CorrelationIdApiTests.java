package com.glofox.memberships.common.api;

import static com.glofox.memberships.common.constant.Constants.API_JSON_CONTENT_TYPE;
import static com.glofox.memberships.common.constant.Constants.IDEMPOTENCY_KEY_HEADER;
import static com.glofox.memberships.common.constant.Constants.INVALID_CORRELATION_ID_CODE;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_COLLECTION_PATH;
import static com.glofox.memberships.membership.constant.Constants.PURCHASE_NOT_FOUND_CODE;
import static com.glofox.memberships.plan.constant.Constants.PLANS_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.config.SeedData;
import com.glofox.memberships.membership.repository.PurchaseSagaRepository;
import com.glofox.memberships.payment.repository.PaymentAttemptRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrelationIdApiTests {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PurchaseSagaRepository sagaRepository;
    @Autowired PaymentAttemptRepository paymentAttemptRepository;

    @Test
    void acceptsValidCorrelationIdAndIncludesItInErrorResponse() throws Exception {
        String id = "req-123_a.b-9";
        var response = mockMvc.perform(get("/v1/memberships/purchases/" + UUID.randomUUID())
                        .header(CorrelationIdContext.HEADER, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PURCHASE_NOT_FOUND_CODE))
                .andExpect(jsonPath("$.correlationId").value(id))
                .andReturn().getResponse();

        assertThat(response.getHeader(CorrelationIdContext.HEADER)).isEqualTo(id);
    }

    @Test
    void generatesCorrelationIdWhenClientOmitsIt() throws Exception {
        var response = mockMvc.perform(get("/v1/memberships/purchases/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse();

        String id = response.getHeader(CorrelationIdContext.HEADER);
        assertThat(id).isNotBlank().hasSize(36);
        ApiError body = objectMapper.readValue(response.getContentAsByteArray(), ApiError.class);
        assertThat(body.correlationId()).isEqualTo(id);
    }

    @Test
    void rejectsInvalidOrOversizedCorrelationIdAndReturnsReplacementId() throws Exception {
        var response = mockMvc.perform(get(PLANS_PATH)
                        .header(CorrelationIdContext.HEADER, "bad id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(INVALID_CORRELATION_ID_CODE))
                .andReturn().getResponse();

        String replacementId = response.getHeader(CorrelationIdContext.HEADER);
        assertThat(replacementId).isNotBlank().hasSize(36);
        ApiError body = objectMapper.readValue(response.getContentAsByteArray(), ApiError.class);
        assertThat(body.correlationId()).isEqualTo(replacementId);
    }

    @Test
    void persistsCorrelationIdWithSagaAndPaymentAttempt() throws Exception {
        String id = "purchase-corr-1";
        mockMvc.perform(post(PURCHASE_COLLECTION_PATH)
                        .header(CorrelationIdContext.HEADER, id)
                        .header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(API_JSON_CONTENT_TYPE)
                        .content("""
                                {"memberName":"Correlation User","planId":"%s","startDate":"2026-09-01"}
                                """.formatted(SeedData.TEN_CLASS_PACK_ID)))
                .andExpect(status().isCreated());

        assertThat(sagaRepository.findAll()).anySatisfy(saga -> assertThat(saga.getCorrelationId()).isEqualTo(id));
        assertThat(paymentAttemptRepository.findAll()).anySatisfy(attempt -> assertThat(attempt.getCorrelationId()).isEqualTo(id));
    }
}
