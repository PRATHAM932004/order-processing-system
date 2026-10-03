package com.orders.payment.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.payment.service.PaymentService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private PaymentConsumer paymentConsumer;

    private UUID orderId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        paymentId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Should process payment and publish PaymentSucceeded when inventory is RESERVED")
    void onInventoryResult_whenStatusReserved_andPaymentSucceeded_publishesPaymentSucceededAndAcknowledges() throws Exception {
        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = EventEnvelope.of("InventoryReserved", inventoryPayload);
        String message = objectMapper.writeValueAsString(envelope);

        PaymentResultPayload paymentResult = new PaymentResultPayload(orderId, "SUCCEEDED", new BigDecimal("1598.00"), paymentId);
        when(paymentService.processPayment(any())).thenReturn(paymentResult);

        paymentConsumer.onInventoryResult(message, acknowledgment);

        verify(kafkaTemplate).send(
                eq(KafkaTopics.ORDER_PAYMENT),
                eq(orderId.toString()),
                contains("PaymentSucceeded")
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should process payment and publish PaymentFailed when payment evaluation fails")
    void onInventoryResult_whenStatusReserved_andPaymentFailed_publishesPaymentFailedAndAcknowledges() throws Exception {
        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = EventEnvelope.of("InventoryReserved", inventoryPayload);
        String message = objectMapper.writeValueAsString(envelope);

        PaymentResultPayload paymentResult = new PaymentResultPayload(orderId, "FAILED", new BigDecimal("3499.00"), paymentId);
        when(paymentService.processPayment(any())).thenReturn(paymentResult);

        paymentConsumer.onInventoryResult(message, acknowledgment);

        verify(kafkaTemplate).send(
                eq(KafkaTopics.ORDER_PAYMENT),
                eq(orderId.toString()),
                contains("PaymentFailed")
        );
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should skip payment processing and acknowledge when inventory outcome is FAILED")
    void onInventoryResult_whenStatusFailed_skipsProcessingAndAcknowledges() throws Exception {
        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "FAILED", "Out of stock");
        EventEnvelope<InventoryResultPayload> envelope = EventEnvelope.of("InventoryFailed", inventoryPayload);
        String message = objectMapper.writeValueAsString(envelope);

        paymentConsumer.onInventoryResult(message, acknowledgment);

        verify(paymentService, never()).processPayment(any());
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should acknowledge without publishing when event is duplicate (service returns null)")
    void onInventoryResult_whenDuplicateEvent_doesNotPublishAndAcknowledges() throws Exception {
        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = EventEnvelope.of("InventoryReserved", inventoryPayload);
        String message = objectMapper.writeValueAsString(envelope);

        when(paymentService.processPayment(any())).thenReturn(null);

        paymentConsumer.onInventoryResult(message, acknowledgment);

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Should withhold acknowledgment when an unhandled exception occurs (e.g., malformed payload)")
    void onInventoryResult_whenMalformedMessage_withholdsAcknowledgment() {
        String invalidMessage = "{invalid-json}";

        paymentConsumer.onInventoryResult(invalidMessage, acknowledgment);

        verify(acknowledgment, never()).acknowledge();
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }
}
