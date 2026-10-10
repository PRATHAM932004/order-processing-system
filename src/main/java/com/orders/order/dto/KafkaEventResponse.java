package com.orders.order.dto;

import java.time.Instant;
import java.util.UUID;

public record KafkaEventResponse(
    String id,
    UUID orderId,
    String eventType,
    String topic,
    int partition,
    long offset,
    Instant timestamp,
    String consumerGroup,
    String payloadSummary,
    Long latencyMs,
    String status
) {}
