package com.orders.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String category,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal
) {
    public OrderItemResponse(UUID id, UUID productId, Integer quantity, BigDecimal unitPrice) {
        this(
            id,
            productId,
            null,
            null,
            quantity,
            unitPrice,
            unitPrice != null && quantity != null
                ? unitPrice.multiply(BigDecimal.valueOf(quantity))
                : BigDecimal.ZERO
        );
    }
}
