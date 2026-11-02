package com.glofox.memberships.payment;

import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;

/**
 * Port for charging a membership purchase.
 * The default adapter always succeeds. Provider adapters must distinguish
 * definitive declines from technical failures.
 */
public interface PaymentGateway {

	PaymentResult charge(PaymentCommand command);

}
