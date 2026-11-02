package com.glofox.memberships.payment;

import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_CHARGE_OPERATION;
import static com.glofox.memberships.testharness.constant.Constants.PAYMENT_TRANSIENT_ERROR_COUNT_SETTING;

import com.glofox.memberships.payment.exceptions.PaymentAmountMismatchException;
import com.glofox.memberships.payment.exceptions.PaymentDeclinedException;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import com.glofox.memberships.testharness.annotation.FaultInjectable;
import com.glofox.memberships.testharness.model.FaultScenario;
import com.glofox.memberships.testharness.service.FaultInvocationContext;
import com.glofox.memberships.testharness.service.SimulatedPaymentLedger;
import com.glofox.memberships.testharness.service.TestHarnessSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AlwaysSucceedingPaymentGateway implements PaymentGateway {
	private final SimulatedPaymentLedger simulatedLedger;
	private final ObjectProvider<TestHarnessSettingsService> settings;

	@Override
	@FaultInjectable(value = PAYMENT_CHARGE_OPERATION, simulationMethod = "simulateCharge")
	public PaymentResult charge(PaymentCommand command) {
		return new PaymentResult(recordCharge(command).transactionId());
	}

	public PaymentResult simulateCharge(PaymentCommand command) {
		TestHarnessSettingsService config = settings.getObject();
		FaultScenario scenario = FaultInvocationContext.current();
		switch (scenario) {
			case SUCCESS, TIMEOUT_AFTER_PROCESSING -> {
				SimulatedPaymentLedger.ChargeResult result = recordCharge(command);
					if (scenario == FaultScenario.TIMEOUT_AFTER_PROCESSING && result.created())
						throw new PaymentProviderException(FailureType.UNKNOWN, "Simulated timeout after processing");
					return new PaymentResult(result.transactionId());
			}
			case DECLINED -> throw new PaymentDeclinedException("Simulated payment decline");
			case PERMANENT_ERROR -> throw new PaymentProviderException(FailureType.PERMANENT, "Simulated provider error");
			case TIMEOUT_BEFORE_PROCESSING -> throw new PaymentProviderException(FailureType.UNKNOWN, "Simulated timeout before processing");
			case TRANSIENT_ERROR -> {
				boolean configured = config.counterConfigured(PAYMENT_TRANSIENT_ERROR_COUNT_SETTING);
				if (!configured || config.consumeCounter(PAYMENT_TRANSIENT_ERROR_COUNT_SETTING))
					throw new PaymentProviderException(FailureType.TRANSIENT, "Simulated transient provider error");
				return new PaymentResult(recordCharge(command).transactionId());
			}
			case NORMAL -> { return charge(command); }
		}
		throw new IllegalStateException("Unsupported payment scenario: " + scenario);
	}

	private SimulatedPaymentLedger.ChargeResult recordCharge(PaymentCommand command) {
		try {
			return simulatedLedger.recordOrReplay(command.idempotencyKey(), command.amountCents());
		} catch (PaymentAmountMismatchException mismatch) {
			// An existing charge cannot safely be treated as a decline or compensated as unpaid.
			throw new PaymentProviderException(FailureType.UNKNOWN, mismatch.getMessage());
		}
	}
}
