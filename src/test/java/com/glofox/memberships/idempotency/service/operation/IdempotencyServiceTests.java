package com.glofox.memberships.idempotency.service.operation;

import static com.glofox.memberships.idempotency.constant.Constants.IN_PROGRESS_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REQUIRED_CODE;
import static com.glofox.memberships.idempotency.constant.Constants.KEY_REUSED_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.common.retry.OptimisticLockRetryPolicy;
import com.glofox.memberships.idempotency.exceptions.IdempotencyException;
import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.entity.IdempotencyRecord;
import com.glofox.memberships.idempotency.service.record.IdempotencyRecordStore;
import com.glofox.memberships.idempotency.service.request.CanonicalPurchaseRequestHasher;
import com.glofox.memberships.membership.api.PurchaseRequest;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IdempotencyServiceTests {

    private final IdempotencyRecordStore store = mock(IdempotencyRecordStore.class);
    private final CanonicalPurchaseRequestHasher hasher = new CanonicalPurchaseRequestHasher();
    private final OptimisticLockRetryPolicy retry = mock(OptimisticLockRetryPolicy.class);
    private final IdempotencyService service = new IdempotencyService(store, hasher, retry);

    @Test
    void trimsNewClientKeyAndPersistsAProviderKey() {
        PurchaseRequest request = request("Alice");
        var prepared = service.prepare(" client-key ", request);
        UUID operationId = UUID.randomUUID();

        IdempotencyService.StartResult result = service.createInProgress(prepared, operationId, "provider-key");

        assertThat(result.clientKey()).isEqualTo("client-key");
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.providerKey()).isEqualTo("provider-key");
        verify(store).create("client-key", hasher.hash(request), operationId, "provider-key");
    }

    @Test
    void returnsCompletedPurchaseForEquivalentRequest() {
        PurchaseRequest request = request("Alice");
        IdempotencyRecord existing = record(request, IdempotencyStatus.COMPLETED);
        UUID purchaseId = existing.getPurchaseId();
        when(store.find("client-key")).thenReturn(Optional.of(existing));

        IdempotencyService.StartResult result = service.findExisting(service.prepare("client-key", request(" alice ")));

        assertThat(result.completedPurchaseId()).isEqualTo(purchaseId);
    }

    @Test
    void rejectsARequestThatReusesKeyWithDifferentContent() {
        PurchaseRequest original = request("Alice");
        when(store.find("client-key")).thenReturn(Optional.of(record(original, IdempotencyStatus.IN_PROGRESS)));

        assertThatThrownBy(() -> service.findExisting(service.prepare("client-key", request("Bob"))))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(failure -> {
                    IdempotencyException exception = (IdempotencyException) failure;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo(KEY_REUSED_CODE);
                });
    }

    @Test
    void rejectsDuplicateRequestWhileOriginalIsInProgress() {
        PurchaseRequest request = request("Alice");
        when(store.find("client-key")).thenReturn(Optional.of(record(request, IdempotencyStatus.IN_PROGRESS)));

        assertThatThrownBy(() -> service.findExisting(service.prepare("client-key", request)))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(failure -> assertThat(((IdempotencyException) failure).getCode()).isEqualTo(IN_PROGRESS_CODE));
    }

    @Test
    void rejectsMissingAndOverlongKeysBeforeStoreAccess() {
        assertThatThrownBy(() -> service.prepare("   ", request("Alice")))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(failure -> assertThat(((IdempotencyException) failure).getCode()).isEqualTo(KEY_REQUIRED_CODE));
        assertThatThrownBy(() -> service.prepare("k".repeat(256), request("Alice")))
                .isInstanceOf(IdempotencyException.class);
    }

    private IdempotencyRecord record(PurchaseRequest request, IdempotencyStatus status) {
        IdempotencyRecord record = new IdempotencyRecord("client-key", hasher.hash(request), UUID.randomUUID(), "provider-key");
        if (status == IdempotencyStatus.COMPLETED) record.complete(UUID.randomUUID());
        return record;
    }

    private PurchaseRequest request(String memberName) {
        return new PurchaseRequest(memberName, UUID.fromString("22222222-2222-2222-2222-222222222222"),
                LocalDate.of(2026, 11, 2));
    }
}
