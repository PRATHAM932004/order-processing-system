package com.orders.order.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreatedPayload(
    UUID orderId,
    UUID customerId,
    List<OrderItemPayload> items,
    BigDecimal totalAmount
) {
    public record OrderItemPayload(
        UUID productId,
        Integer quantity,
        BigDecimal unitPrice
    ) {}
}
