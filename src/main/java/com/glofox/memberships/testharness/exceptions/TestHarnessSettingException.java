package com.glofox.memberships.testharness.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class TestHarnessSettingException extends ApiException {
    public TestHarnessSettingException(HttpStatus status, String code, String message) { super(status, code, message); }
}
