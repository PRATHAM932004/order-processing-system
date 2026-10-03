package com.orders.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID id,
    UUID productId,
    Integer quantity,
    BigDecimal unitPrice
) {}
