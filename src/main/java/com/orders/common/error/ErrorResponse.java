package com.orders.common.error;

import java.time.Instant;

public record ErrorResponse(
    Instant timestamp,
    String code,
    String message,
    String path,
    String error
) {
    public ErrorResponse(String code, String message, String path) {
        this(Instant.now(), code, message, path, message);
    }
}
