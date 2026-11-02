package com.glofox.memberships.membership.saga.recovery.payment;

import com.glofox.memberships.membership.saga.recovery.config.RecoveryProperties;
import com.glofox.memberships.payment.PaymentGateway;
import com.glofox.memberships.payment.exceptions.PaymentProviderException;
import com.glofox.memberships.payment.model.FailureType;
import com.glofox.memberships.payment.model.PaymentCommand;
import com.glofox.memberships.payment.model.PaymentResult;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** One worker, no queue. A timed-out provider operation is ambiguous, never a definitive failure. */
@Service
public class RecoveryPaymentGateway {
    private final PaymentGateway gateway;
    private final RecoveryProperties properties;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "saga-recovery-payment");
                thread.setDaemon(true);
                return thread;
            });

    public RecoveryPaymentGateway(PaymentGateway gateway, RecoveryProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public PaymentResult charge(PaymentCommand command) {
        var context = MDC.getCopyOfContextMap();
        Future<PaymentResult> future;
        try {
            future = worker.submit(() -> {
                if (context != null) MDC.setContextMap(context);
                try { return gateway.charge(command); }
                finally { MDC.clear(); }
            });
        } catch (RejectedExecutionException busy) {
            throw new PaymentProviderException(FailureType.TRANSIENT, "Recovery provider worker is busy");
        }
        try {
            return future.get(properties.getProviderTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new PaymentProviderException(FailureType.UNKNOWN, "Recovery provider response timed out");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PaymentProviderException(FailureType.UNKNOWN, "Recovery provider wait was interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new PaymentProviderException(FailureType.UNKNOWN, "Recovery provider failed unexpectedly");
        }
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
