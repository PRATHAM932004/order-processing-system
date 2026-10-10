package com.orders.order.dto;

import java.time.Instant;

public record SystemStatusResponse(
    String backendStatus,
    int backendPort,
    String dbStatus,
    String dbEndpoint,
    long dbPingMs,
    String kafkaStatus,
    String kafkaBootstrapServers,
    int activeBrokers,
    int activeConsumerGroups,
    int totalConsumerGroups,
    long outboxPollIntervalMs,
    int outboxBatchSize,
    long unpublishedOutboxCount,
    Long lastOutboxRelayMs,
    long endToEndLatencyMs,
    Instant timestamp
) {}
