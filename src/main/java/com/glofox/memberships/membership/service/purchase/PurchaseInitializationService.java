package com.glofox.memberships.membership.service.purchase;

import static com.glofox.memberships.idempotency.constant.Constants.PROVIDER_KEY_PREFIX;

import com.glofox.memberships.common.exception.ApiException;
import com.glofox.memberships.common.observability.CorrelationIdContext;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.PreparedRequest;
import com.glofox.memberships.idempotency.service.operation.IdempotencyService.StartResult;
import com.glofox.memberships.membership.api.PurchaseRequest;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Validates before initialization and resolves same-key races after transactional rollback. */
@Service
@RequiredArgsConstructor
public class PurchaseInitializationService {

    private final IdempotencyService idempotency;
    private final PlanPurchaseLookup planLookup;
    private final PurchaseInitializationTransaction transaction;

    public InitializedPurchase initialize(String rawKey, PurchaseRequest request) {
        PreparedRequest prepared = idempotency.prepare(rawKey, request);
        StartResult existing = idempotency.findExisting(prepared);
        if (existing != null) return new InitializedPurchase(existing, null);

        UUID operationId = UUID.randomUUID();
        String providerKey = PROVIDER_KEY_PREFIX + operationId;
        PlanPurchaseDetails plan;
        try {
            plan = planLookup.findActivePlan(request.planId());
        } catch (ApiException failure) {
            try {
                StartResult result = transaction.createFailed(prepared, operationId, providerKey, failure);
                return new InitializedPurchase(result, null);
            } catch (DataIntegrityViolationException concurrentInsert) {
                return resolveConcurrentRequest(prepared, concurrentInsert);
            }
        }

        String memberKey = request.memberName().trim().toLowerCase(Locale.ROOT);
        try {
            StartResult result = transaction.createInProgress(prepared, operationId, providerKey, request,
                    memberKey, plan, CorrelationIdContext.currentOr(operationId.toString()));
            return new InitializedPurchase(result, plan);
        } catch (DataIntegrityViolationException concurrentInsert) {
            return resolveConcurrentRequest(prepared, concurrentInsert);
        }
    }

    private InitializedPurchase resolveConcurrentRequest(
            PreparedRequest prepared, DataIntegrityViolationException concurrentInsert) {
        StartResult raced = idempotency.findExisting(prepared);
        if (raced == null) throw concurrentInsert;
        return new InitializedPurchase(raced, null);
    }

    public record InitializedPurchase(StartResult operation, PlanPurchaseDetails plan) {}
}
