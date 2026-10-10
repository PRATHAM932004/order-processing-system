package com.orders.order.dto;

import com.orders.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    UUID customerId,
    String customerName,
    String customerEmail,
    OrderStatus status,
    String currentPhase,
    String failureReason,
    BigDecimal totalAmount,
    List<OrderItemResponse> items,
    Instant createdAt,
    Instant updatedAt
) {
    public OrderResponse(
        UUID id,
        UUID customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(
            id,
            customerId,
            null,
            null,
            status,
            status == OrderStatus.CONFIRMED ? "ORDER_CONFIRMED"
                : status == OrderStatus.CANCELLED ? "FAILED" : "ORDER_PLACED",
            null,
            totalAmount,
            items,
            createdAt,
            updatedAt
        );
    }
}
