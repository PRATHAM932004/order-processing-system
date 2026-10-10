package com.orders.order.dto;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
    UUID id,
    String name,
    String email,
    String role,
    Instant createdAt
) {}
