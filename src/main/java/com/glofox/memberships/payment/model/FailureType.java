package com.glofox.memberships.payment.model;

/** Distinguishes retryable outages, permanent technical failures and uncertain payment outcomes. */
public enum FailureType {
    TRANSIENT,
    PERMANENT,
    UNKNOWN
}
