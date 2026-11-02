package com.glofox.memberships.plan.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PlanException extends ApiException {
    public PlanException(HttpStatus status, String code, String message) { super(status, code, message); }
}
