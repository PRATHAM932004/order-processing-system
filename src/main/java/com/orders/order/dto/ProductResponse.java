package com.orders.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(
    UUID id,
    String name,
    String category,
    BigDecimal price,
    Integer stockQuantity
) {}
