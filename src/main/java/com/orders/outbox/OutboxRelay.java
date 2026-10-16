package com.orders.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${orders.outbox.batch-size:50}")
    private int batchSize;

    @Value("${orders.outbox.send-timeout-seconds:10}")
    private long sendTimeoutSeconds = 10;

    @Scheduled(fixedDelayString = "${orders.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relayEvents() {
        List<OutboxEvent> unpublishedEvents = outboxEventRepository
                .findUnpublishedEvents(PageRequest.of(0, batchSize));

        if (unpublishedEvents.isEmpty()) {
            return;
        }

        log.debug("Found {} unpublished outbox events to relay", unpublishedEvents.size());

        for (OutboxEvent event : unpublishedEvents) {
            try {
                String key = event.getAggregateId() != null ? event.getAggregateId().toString() : null;
                CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(event.getTopic(), key, event.getPayload());
                if (future == null) {
                    throw new IllegalStateException("KafkaTemplate send returned null future");
                }
                future.get(sendTimeoutSeconds, TimeUnit.SECONDS);
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                log.info("Relayed outbox event: {} to topic: {}", event.getId(), event.getTopic());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for Kafka send ack for outbox event: {} to topic: {}", event.getId(), event.getTopic(), e);
            } catch (Exception e) {
                log.error("Failed to relay outbox event: {} to topic: {}", event.getId(), event.getTopic(), e);
                // Leave publishedAt as null so it will be retried in the next polling cycle (BR-008)
            }
        }
    }
}
