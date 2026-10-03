package com.orders.order.event;

import java.util.UUID;

public record InventoryResultPayload(
    UUID orderId,
    String status,
    String failureReason
) {}
