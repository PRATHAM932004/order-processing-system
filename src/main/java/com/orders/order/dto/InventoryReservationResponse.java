package com.orders.order.dto;

import com.orders.inventory.entity.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record InventoryReservationResponse(
    UUID id,
    UUID orderId,
    UUID productId,
    String productName,
    String category,
    Integer quantity,
    ReservationStatus status,
    Instant createdAt
) {}
