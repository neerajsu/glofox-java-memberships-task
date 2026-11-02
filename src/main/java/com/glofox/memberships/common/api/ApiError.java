package com.glofox.memberships.common.api;

public record ApiError(String code, String message, String correlationId) {
}
