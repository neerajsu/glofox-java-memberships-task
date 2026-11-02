package com.glofox.memberships.idempotency.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.glofox.memberships.idempotency.model.IdempotencyStatus;
import com.glofox.memberships.idempotency.repository.IdempotencyRecordRepository;
import com.glofox.memberships.idempotency.repository.entity.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IdempotencyRecordStoreTests {

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final IdempotencyRecordStore store = new IdempotencyRecordStore(repository);

    @Test
    void createsRecordUsingClientAndProviderKeys() {
        store.create("client-key", "request-hash", UUID.randomUUID(), "provider-key");

        org.mockito.ArgumentCaptor<IdempotencyRecord> captor =
                org.mockito.ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getClientKey()).isEqualTo("client-key");
        assertThat(captor.getValue().getProviderIdempotencyKey()).isEqualTo("provider-key");
        assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    void failIfInProgressDoesNotOverwriteTerminalFailure() {
        IdempotencyRecord record = new IdempotencyRecord("client-key", "hash", UUID.randomUUID(), "provider-key");
        record.fail(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", "declined");
        when(repository.findById("client-key")).thenReturn(Optional.of(record));

        store.failIfInProgress("client-key", HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_ERROR", "unavailable");

        assertThat(record.getFailureCode()).isEqualTo("PAYMENT_DECLINED");
    }
}
