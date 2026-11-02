package com.glofox.memberships.testharness.service;

import com.glofox.memberships.payment.exceptions.PaymentAmountMismatchException;
import com.glofox.memberships.testharness.repository.SimulatedPaymentChargeRepository;
import com.glofox.memberships.testharness.repository.entity.SimulatedPaymentCharge;
import com.glofox.memberships.testharness.service.SimulatedPaymentLedger.ChargeResult;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SimulatedPaymentLedgerTransaction {
    private final SimulatedPaymentChargeRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChargeResult recordOrReplay(String providerKey, int amountCents) {
        Optional<ChargeResult> existing = lookup(providerKey, amountCents);
        if (existing.isPresent()) return existing.get();
        String transactionId = "sim-txn-" + UUID.randomUUID();
        SimulatedPaymentCharge created = repository.saveAndFlush(
                new SimulatedPaymentCharge(providerKey, transactionId, amountCents));
        return new ChargeResult(created.getTransactionId(), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ChargeResult> findRecorded(String providerKey, int amountCents) {
        return lookup(providerKey, amountCents);
    }

    private Optional<ChargeResult> lookup(String providerKey, int amountCents) {
        return repository.findByProviderKey(providerKey).map(charge -> {
            if (charge.getAmountCents() != amountCents) throw new PaymentAmountMismatchException();
            return new ChargeResult(charge.getTransactionId(), false);
        });
    }
}
