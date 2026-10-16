package com.orders.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.inventory.service.InventoryService;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.event.PaymentResultPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryConsumerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private InventoryConsumer inventoryConsumer;

    private UUID orderId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        productId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should publish InventoryReserved to order.inventory and acknowledge on successful reservation")
    void onOrderCreated_whenReservationSuccess_publishesInventoryReservedAndAcknowledges() throws Exception {
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(new OrderCreatedPayload.OrderItemPayload(productId, 2, new BigDecimal("50.00"))),
                new BigDecimal("100.00")
        );
        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelope.of("OrderCreated", payload);
        String message = objectMapper.writeValueAsString(envelope);

        when(inventoryService.reserveInventory(any()))
                .thenReturn(new InventoryResultPayload(orderId, "RESERVED", null));

        inventoryConsumer.onOrderCreated(message, acknowledgment);

        verify(kafkaTemplate).send(
                eq(KafkaTopics.ORDER_INVENTORY),
                eq(orderId.toString()),
                contains("InventoryReserved")
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should publish InventoryFailed to order.inventory and acknowledge on failed reservation")
    void onOrderCreated_whenReservationFailed_publishesInventoryFailedAndAcknowledges() throws Exception {
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(new OrderCreatedPayload.OrderItemPayload(productId, 10, new BigDecimal("50.00"))),
                new BigDecimal("500.00")
        );
        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelope.of("OrderCreated", payload);
        String message = objectMapper.writeValueAsString(envelope);

        when(inventoryService.reserveInventory(any()))
                .thenReturn(new InventoryResultPayload(orderId, "FAILED", "Insufficient stock"));

        inventoryConsumer.onOrderCreated(message, acknowledgment);

        verify(kafkaTemplate).send(
                eq(KafkaTopics.ORDER_INVENTORY),
                eq(orderId.toString()),
                contains("InventoryFailed")
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should skip publishing but acknowledge when duplicate event is ignored (returns null)")
    void onOrderCreated_whenDuplicate_doesNotPublishAndAcknowledges() throws Exception {
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(new OrderCreatedPayload.OrderItemPayload(productId, 1, new BigDecimal("50.00"))),
                new BigDecimal("50.00")
        );
        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelope.of("OrderCreated", payload);
        String message = objectMapper.writeValueAsString(envelope);

        when(inventoryService.reserveInventory(any())).thenReturn(null);

        inventoryConsumer.onOrderCreated(message, acknowledgment);

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should invoke compensateInventory and acknowledge when PaymentFailed event received")
    void onPaymentResult_whenPaymentFailed_callsCompensateInventoryAndAcknowledges() throws Exception {
        PaymentResultPayload payload = new PaymentResultPayload(
                orderId,
                "FAILED",
                new BigDecimal("100.00"),
                UUID.randomUUID()
        );
        EventEnvelope<PaymentResultPayload> envelope = EventEnvelope.of("PaymentFailed", payload);
        String message = objectMapper.writeValueAsString(envelope);

        inventoryConsumer.onPaymentResult(message, acknowledgment);

        verify(inventoryService).compensateInventory(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should ignore PaymentSucceeded event for compensation and acknowledge")
    void onPaymentResult_whenPaymentSucceeded_ignoresAndAcknowledges() throws Exception {
        PaymentResultPayload payload = new PaymentResultPayload(
                orderId,
                "SUCCEEDED",
                new BigDecimal("100.00"),
                UUID.randomUUID()
        );
        EventEnvelope<PaymentResultPayload> envelope = EventEnvelope.of("PaymentSucceeded", payload);
        String message = objectMapper.writeValueAsString(envelope);

        inventoryConsumer.onPaymentResult(message, acknowledgment);

        verify(inventoryService, never()).compensateInventory(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should withhold acknowledgment and rethrow exception on unhandled error in onOrderCreated")
    void onOrderCreated_whenMalformedMessage_withholdsAcknowledgmentAndThrows() {
        String invalidMessage = "{invalid-json}";

        assertThatThrownBy(() -> inventoryConsumer.onOrderCreated(invalidMessage, acknowledgment))
                .isInstanceOf(Exception.class);

        verify(acknowledgment, never()).acknowledge();
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("Should withhold acknowledgment and rethrow exception on unhandled error in onPaymentResult")
    void onPaymentResult_whenMalformedMessage_withholdsAcknowledgmentAndThrows() {
        String invalidMessage = "{invalid-json}";

        assertThatThrownBy(() -> inventoryConsumer.onPaymentResult(invalidMessage, acknowledgment))
                .isInstanceOf(Exception.class);

        verify(acknowledgment, never()).acknowledge();
        verify(inventoryService, never()).compensateInventory(any());
    }
}
