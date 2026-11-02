package com.glofox.memberships.membership.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class MembershipException extends ApiException {
    public MembershipException(HttpStatus status, String code, String message) { super(status, code, message); }
}
