package com.orders.order.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.ProcessedEvent;
import com.orders.common.event.ProcessedEventRepository;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderStatus;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderStatusConsumerTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private Acknowledgment acknowledgment;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderStatusConsumer orderStatusConsumer;

    private UUID orderId;
    private UUID eventId;
    private Order order;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        eventId = UUID.randomUUID();

        order = new Order(UUID.randomUUID(), new BigDecimal("1000.00"));
        order.setId(orderId);
        order.setStatus(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("handleInventoryResult sets order status to CANCELLED on InventoryFailed")
    void handleInventoryResult_updatesStatusToCancelled_whenInventoryFailed() {
        InventoryResultPayload payload = new InventoryResultPayload(orderId, "FAILED", "Insufficient stock");
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(
                eventId,
                "InventoryFailed",
                Instant.now(),
                payload
        );

        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, OrderStatusConsumer.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderStatusConsumer.handleInventoryResult(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);

        ArgumentCaptor<ProcessedEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processedCaptor.capture());
        assertThat(processedCaptor.getValue().getEventId()).isEqualTo(eventId);
        assertThat(processedCaptor.getValue().getConsumerName()).isEqualTo(OrderStatusConsumer.CONSUMER_NAME);
    }

    @Test
    @DisplayName("handleInventoryResult ignores duplicate event")
    void handleInventoryResult_skipsProcessing_whenEventAlreadyProcessed() {
        InventoryResultPayload payload = new InventoryResultPayload(orderId, "FAILED", "Insufficient stock");
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(
                eventId,
                "InventoryFailed",
                Instant.now(),
                payload
        );

        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, OrderStatusConsumer.CONSUMER_NAME))
                .thenReturn(true);

        orderStatusConsumer.handleInventoryResult(envelope);

        verify(orderRepository, never()).findById(any());
        verify(orderRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("handlePaymentResult sets order status to CONFIRMED on PaymentSucceeded")
    void handlePaymentResult_updatesStatusToConfirmed_whenPaymentSucceeded() {
        PaymentResultPayload payload = new PaymentResultPayload(orderId, "SUCCEEDED", new BigDecimal("1000.00"), UUID.randomUUID());
        EventEnvelope<PaymentResultPayload> envelope = new EventEnvelope<>(
                eventId,
                "PaymentSucceeded",
                Instant.now(),
                payload
        );

        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, OrderStatusConsumer.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderStatusConsumer.handlePaymentResult(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository).save(order);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("handlePaymentResult sets order status to CANCELLED on PaymentFailed")
    void handlePaymentResult_updatesStatusToCancelled_whenPaymentFailed() {
        PaymentResultPayload payload = new PaymentResultPayload(orderId, "FAILED", new BigDecimal("1000.00"), UUID.randomUUID());
        EventEnvelope<PaymentResultPayload> envelope = new EventEnvelope<>(
                eventId,
                "PaymentFailed",
                Instant.now(),
                payload
        );

        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, OrderStatusConsumer.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        orderStatusConsumer.handlePaymentResult(envelope);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("onInventoryResult withholds acknowledgment and rethrows on unhandled exception")
    void onInventoryResult_whenMalformedMessage_withholdsAcknowledgmentAndThrows() {
        String invalidMessage = "{invalid-json}";

        assertThatThrownBy(() -> orderStatusConsumer.onInventoryResult(invalidMessage, acknowledgment))
                .isInstanceOf(Exception.class);

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("onPaymentResult withholds acknowledgment and rethrows on unhandled exception")
    void onPaymentResult_whenMalformedMessage_withholdsAcknowledgmentAndThrows() {
        String invalidMessage = "{invalid-json}";

        assertThatThrownBy(() -> orderStatusConsumer.onPaymentResult(invalidMessage, acknowledgment))
                .isInstanceOf(Exception.class);

        verify(acknowledgment, never()).acknowledge();
    }
}
