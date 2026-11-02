package com.glofox.memberships.idempotency.service.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.glofox.memberships.membership.api.PurchaseRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalPurchaseRequestHasherTests {

    private final CanonicalPurchaseRequestHasher hasher = new CanonicalPurchaseRequestHasher();

    @Test
    void hashesEquivalentMemberNameCasingAndWhitespaceIdentically() {
        UUID planId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2026, 11, 2);

        String normalized = hasher.hash(new PurchaseRequest(" Alice ", planId, startDate));
        String differentlyFormatted = hasher.hash(new PurchaseRequest("alice", planId, startDate));

        assertThat(normalized).isEqualTo(differentlyFormatted).hasSize(64);
    }

    @Test
    void hashesDifferentRequestFieldsDifferently() {
        UUID planId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 2);
        String original = hasher.hash(new PurchaseRequest("Alice", planId, date));

        assertThat(hasher.hash(new PurchaseRequest("Alice", UUID.randomUUID(), date))).isNotEqualTo(original);
        assertThat(hasher.hash(new PurchaseRequest("Alice", planId, date.plusDays(1)))).isNotEqualTo(original);
    }
}
