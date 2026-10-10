package com.orders.security;

import java.util.UUID;

public record AuthenticatedUser(
    UUID id,
    String name,
    String email,
    String role
) {
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
