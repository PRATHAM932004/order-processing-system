package com.orders.order.event;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResultPayload(
    UUID orderId,
    String status,
    BigDecimal amount,
    UUID paymentId
) {}
