package com.orders.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${orders.outbox.batch-size:50}")
    private int batchSize;

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
                kafkaTemplate.send(event.getTopic(), key, event.getPayload());
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                log.info("Relayed outbox event: {} to topic: {}", event.getId(), event.getTopic());
            } catch (Exception e) {
                log.error("Failed to relay outbox event: {} to topic: {}", event.getId(), event.getTopic(), e);
                // Leave publishedAt as null so it will be retried in the next polling cycle (BR-008)
            }
        }
    }
}
