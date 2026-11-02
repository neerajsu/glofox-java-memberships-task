package com.glofox.memberships.testharness.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import com.glofox.memberships.testharness.repository.entity.SimulatedPaymentCharge;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulatedPaymentLedgerTransactionTests {

    @Test
    void returnsExistingChargeForRepeatedProviderKey() {
        SimulatedPaymentChargeRepository repository = mock(SimulatedPaymentChargeRepository.class);
        SimulatedPaymentCharge existing = new SimulatedPaymentCharge("provider-key", "txn-existing", 5000);
        when(repository.findByProviderKey("provider-key")).thenReturn(Optional.of(existing));

        var result = new SimulatedPaymentLedgerTransaction(repository).recordOrReplay("provider-key", 5000);

        assertThat(result.transactionId()).isEqualTo("txn-existing");
        assertThat(result.created()).isFalse();
    }

    @Test
    void recordsNewChargeWhenProviderKeyHasNotBeenSeen() {
        SimulatedPaymentChargeRepository repository = mock(SimulatedPaymentChargeRepository.class);
        when(repository.findByProviderKey("provider-key")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = new SimulatedPaymentLedgerTransaction(repository).recordOrReplay("provider-key", 5000);

        assertThat(result.transactionId()).startsWith("sim-txn-");
        assertThat(result.created()).isTrue();
    }
}
