package com.glofox.memberships.testharness.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SimulatedPaymentLedgerTests {
    @Test
    void concurrentInsertFailureReloadsThroughTheSeparateTransactionCollaborator() {
        var transaction = mock(SimulatedPaymentLedgerTransaction.class);
        var collision = new DataIntegrityViolationException("unique provider key");
        var winner = new SimulatedPaymentLedger.ChargeResult("winner", false);
        when(transaction.recordOrReplay("key", 5000)).thenThrow(collision);
        when(transaction.findRecorded("key", 5000)).thenReturn(Optional.of(winner));

        assertThat(new SimulatedPaymentLedger(transaction).recordOrReplay("key", 5000)).isEqualTo(winner);
        verify(transaction).findRecorded("key", 5000);
    }

    @Test
    void unrelatedIntegrityFailureIsNotHiddenWhenThereIsNoWinningProviderRecord() {
        var transaction = mock(SimulatedPaymentLedgerTransaction.class);
        var failure = new DataIntegrityViolationException("other constraint");
        when(transaction.recordOrReplay("key", 5000)).thenThrow(failure);
        when(transaction.findRecorded("key", 5000)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new SimulatedPaymentLedger(transaction).recordOrReplay("key", 5000))
                .isSameAs(failure);
    }
}
