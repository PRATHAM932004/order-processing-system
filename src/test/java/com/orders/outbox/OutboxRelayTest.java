package com.orders.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxRelay, "batchSize", 50);
    }

    @Test
    @DisplayName("Should poll and publish unpublished outbox events to Kafka and set publishedAt")
    void relayEvents_whenUnpublishedEventsExist_publishesToKafkaAndUpdatesTimestamp() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(aggregateId, "OrderCreated", "order.created", "{\"payload\":\"test\"}");

        when(outboxEventRepository.findUnpublishedEvents(any(Pageable.class)))
                .thenReturn(List.of(event));

        outboxRelay.relayEvents();

        verify(kafkaTemplate).send("order.created", aggregateId.toString(), "{\"payload\":\"test\"}");
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Should do nothing when no unpublished events exist")
    void relayEvents_whenNoEvents_doesNothing() {
        when(outboxEventRepository.findUnpublishedEvents(any(Pageable.class)))
                .thenReturn(Collections.emptyList());

        outboxRelay.relayEvents();

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should leave publishedAt as null if Kafka send throws an exception (BR-008 retry)")
    void relayEvents_whenKafkaFails_leavesPublishedAtNullForRetry() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(aggregateId, "OrderCreated", "order.created", "{\"payload\":\"test\"}");

        when(outboxEventRepository.findUnpublishedEvents(any(Pageable.class)))
                .thenReturn(List.of(event));
        doThrow(new RuntimeException("Kafka unreachable"))
                .when(kafkaTemplate).send(any(), any(), any());

        outboxRelay.relayEvents();

        assertThat(event.getPublishedAt()).isNull();
        verify(outboxEventRepository, never()).save(event);
    }
}
