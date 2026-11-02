package com.glofox.memberships.testharness.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** DB-backed simulated provider ledger; durability follows the configured database (in-memory H2 by default). */
@Service
@RequiredArgsConstructor
public class SimulatedPaymentLedger {
    private final SimulatedPaymentLedgerTransaction transaction;

    public ChargeResult recordOrReplay(String providerKey, int amountCents) {
        try {
            return transaction.recordOrReplay(providerKey, amountCents);
        } catch (DataIntegrityViolationException concurrentInsert) {
            // The failed REQUIRES_NEW invocation has rolled back before this fresh proxy invocation.
            return transaction.findRecorded(providerKey, amountCents).orElseThrow(() -> concurrentInsert);
        }
    }
    public record ChargeResult(String transactionId, boolean created) {}
}
