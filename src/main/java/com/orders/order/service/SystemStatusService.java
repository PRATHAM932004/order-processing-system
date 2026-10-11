package com.orders.order.service;

import com.orders.order.dto.SystemStatusResponse;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderStatus;
import com.orders.order.repository.OrderRepository;
import com.orders.outbox.OutboxEvent;
import com.orders.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SystemStatusService {

    private final DataSource dataSource;
    private final OutboxEventRepository outboxEventRepository;
    private final OrderRepository orderRepository;
    private final Environment environment;

    @Autowired(required = false)
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;

    @Value("${orders.outbox.poll-interval-ms:1000}")
    private long outboxPollIntervalMs;

    @Value("${orders.outbox.batch-size:50}")
    private int outboxBatchSize;

    public SystemStatusResponse getSystemStatus() {
        int serverPort = Integer.parseInt(environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080")));

        // 1. Live PostgreSQL Health & Ping Latency
        String dbStatus = "DOWN";
        String dbEndpoint = ":5432";
        long dbPingMs = 0L;
        long startNs = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                dbStatus = "HEALTHY";
            }
            dbPingMs = Math.max(1L, Duration.ofNanos(System.nanoTime() - startNs).toMillis());
            String url = connection.getMetaData().getURL();
            if (url != null && url.contains("://")) {
                String hostPortPart = url.substring(url.indexOf("://") + 3);
                int slashIdx = hostPortPart.indexOf('/');
                if (slashIdx > 0) {
                    String hostPort = hostPortPart.substring(0, slashIdx);
                    if (hostPort.startsWith("localhost:") || hostPort.startsWith("127.0.0.1:")) {
                        dbEndpoint = ":" + hostPort.substring(hostPort.lastIndexOf(':') + 1);
                    } else {
                        dbEndpoint = hostPort;
                    }
                }
            }
        } catch (Exception ignored) {
            dbStatus = "DOWN";
        }

        // 2. Live Kafka Broker TCP Check & Consumer Group Inspection
        boolean brokerReachable = isBrokerReachable(kafkaBootstrapServers);
        int totalGroups = 0;
        int activeGroups = 0;

        if (kafkaListenerEndpointRegistry != null) {
            Collection<MessageListenerContainer> containers = kafkaListenerEndpointRegistry.getAllListenerContainers();
            Set<String> allGroupIds = new HashSet<>();
            Set<String> runningGroupIds = new HashSet<>();
            for (MessageListenerContainer container : containers) {
                String groupId = container.getGroupId() != null ? container.getGroupId() : container.getListenerId();
                allGroupIds.add(groupId);
                if (container.isRunning()) {
                    runningGroupIds.add(groupId);
                }
            }
            totalGroups = allGroupIds.size();
            activeGroups = brokerReachable ? runningGroupIds.size() : 0;
        }

        String kafkaStatus = (brokerReachable && activeGroups > 0)
                ? "OPERATIONAL"
                : brokerReachable ? "DEGRADED" : "DOWN";
        int activeBrokers = brokerReachable ? 1 : 0;

        // 3. Live Outbox Relay Metrics
        long unpublishedCount = 0L;
        Long lastOutboxRelayMs = null;
        try {
            unpublishedCount = outboxEventRepository.countByPublishedAtIsNull();
            List<OutboxEvent> recentEvents = outboxEventRepository.findTop50ByOrderByCreatedAtDesc();
            for (OutboxEvent event : recentEvents) {
                if (event.getPublishedAt() != null && event.getCreatedAt() != null) {
                    lastOutboxRelayMs = Math.max(
                            1L,
                            Duration.between(event.getCreatedAt(), event.getPublishedAt()).toMillis()
                    );
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        // 4. Live End-to-End Order Processing Latency
        long endToEndLatencyMs = dbPingMs;
        try {
            List<Order> recentOrders = orderRepository.findAllWithItemsOrderByCreatedAtDesc();
            for (Order order : recentOrders) {
                if ((order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.CANCELLED)
                        && order.getCreatedAt() != null
                        && order.getUpdatedAt() != null) {
                    long diffMs = Duration.between(order.getCreatedAt(), order.getUpdatedAt()).toMillis();
                    if (diffMs > 0) {
                        endToEndLatencyMs = diffMs;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return new SystemStatusResponse(
                "CONNECTED",
                serverPort,
                dbStatus,
                dbEndpoint,
                dbPingMs,
                kafkaStatus,
                kafkaBootstrapServers,
                activeBrokers,
                activeGroups,
                totalGroups,
                outboxPollIntervalMs,
                outboxBatchSize,
                unpublishedCount,
                lastOutboxRelayMs,
                endToEndLatencyMs,
                Instant.now()
        );
    }

    private boolean isBrokerReachable(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            return false;
        }
        String firstBroker = bootstrapServers.split(",")[0].trim();
        if (firstBroker.contains("://")) {
            firstBroker = firstBroker.substring(firstBroker.indexOf("://") + 3);
        }
        String[] parts = firstBroker.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9092;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
