package com.orders.payment.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentConsumer {

    private final PaymentService paymentService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ORDER_INVENTORY, groupId = PaymentService.CONSUMER_NAME)
    public void onInventoryResult(String message, Acknowledgment acknowledgment) {
        try {
            EventEnvelope<InventoryResultPayload> envelope = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            InventoryResultPayload payload = envelope.payload();

            // Only process payment if inventory was successfully RESERVED
            if (!"RESERVED".equalsIgnoreCase(payload.status())) {
                log.debug("Skipping payment processing for non-reserved inventory outcome: {} on order: {}",
                        payload.status(), payload.orderId());
                acknowledgment.acknowledge();
                return;
            }

            log.info("Received InventoryReserved event for order: {}, eventId: {}",
                    payload.orderId(), envelope.eventId());

            PaymentResultPayload result = paymentService.processPayment(envelope);

            if (result != null) {
                String eventType = "SUCCEEDED".equalsIgnoreCase(result.status())
                        ? "PaymentSucceeded"
                        : "PaymentFailed";

                EventEnvelope<PaymentResultPayload> outEnvelope = EventEnvelope.of(eventType, result);
                String resultMessage = objectMapper.writeValueAsString(outEnvelope);

                kafkaTemplate.send(
                        KafkaTopics.ORDER_PAYMENT,
                        result.orderId().toString(),
                        resultMessage
                );

                log.info("Published {} event for order: {}", eventType, result.orderId());
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process inventory result event for payment: {}", message, e);
            // Withhold acknowledgment to enable Kafka redelivery on unexpected failure
        }
    }
}
