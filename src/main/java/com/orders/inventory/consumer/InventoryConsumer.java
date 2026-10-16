package com.orders.inventory.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.inventory.service.InventoryService;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.event.PaymentResultPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryConsumer {

    private final InventoryService inventoryService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ORDER_CREATED, groupId = InventoryService.CONSUMER_NAME)
    public void onOrderCreated(String message, Acknowledgment acknowledgment) throws Exception {
        try {
            EventEnvelope<OrderCreatedPayload> envelope = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            log.info("Received OrderCreated event for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());

            InventoryResultPayload result = inventoryService.reserveInventory(envelope);

            if (result != null) {
                String eventType = "RESERVED".equalsIgnoreCase(result.status())
                        ? "InventoryReserved"
                        : "InventoryFailed";

                EventEnvelope<InventoryResultPayload> outEnvelope = EventEnvelope.of(eventType, result);
                String resultMessage = objectMapper.writeValueAsString(outEnvelope);

                kafkaTemplate.send(
                        KafkaTopics.ORDER_INVENTORY,
                        result.orderId().toString(),
                        resultMessage
                );

                log.info("Published {} event for order: {}", eventType, result.orderId());
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process OrderCreated event message: {}", message, e);
            throw e;
        }
    }

    @KafkaListener(topics = KafkaTopics.ORDER_PAYMENT, groupId = InventoryService.COMPENSATION_CONSUMER_NAME)
    public void onPaymentResult(String message, Acknowledgment acknowledgment) throws Exception {
        try {
            EventEnvelope<PaymentResultPayload> envelope = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            log.info("Received PaymentResult event for order: {}, status: {}",
                    envelope.payload().orderId(), envelope.payload().status());

            if ("FAILED".equalsIgnoreCase(envelope.payload().status())) {
                inventoryService.compensateInventory(envelope);
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process PaymentResult event for compensation: {}", message, e);
            throw e;
        }
    }
}
