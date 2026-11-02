package com.glofox.memberships.inventory.exceptions;

import com.glofox.memberships.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InventoryException extends ApiException {
    public InventoryException(HttpStatus status, String code, String message) { super(status, code, message); }
}
