package com.orders.order.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.common.event.ProcessedEvent;
import com.orders.common.event.ProcessedEventRepository;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderStatus;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStatusConsumer {

    public static final String CONSUMER_NAME = "order-status-updater";

    private final OrderRepository orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ORDER_INVENTORY, groupId = CONSUMER_NAME)
    public void onInventoryResult(String message, Acknowledgment acknowledgment) throws Exception {
        try {
            EventEnvelope<InventoryResultPayload> envelope = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            log.info("Received inventory result for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());

            handleInventoryResult(envelope);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process inventory result message: {}", message, e);
            throw e;
        }
    }

    @Transactional
    public void handleInventoryResult(EventEnvelope<InventoryResultPayload> envelope) {
        if (processedEventRepository.existsByEventIdAndConsumerName(envelope.eventId(), CONSUMER_NAME)) {
            log.info("Duplicate event ignored for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());
            return;
        }

        InventoryResultPayload payload = envelope.payload();

        if ("FAILED".equalsIgnoreCase(payload.status())) {
            Order order = orderRepository.findById(payload.orderId())
                    .orElse(null);

            if (order != null) {
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
                log.info("Order status updated to CANCELLED for order: {}", payload.orderId());
            } else {
                log.warn("Order not found for inventory failure update: {}", payload.orderId());
            }
        } else if ("RESERVED".equalsIgnoreCase(payload.status())) {
            log.info("Inventory reserved for order: {}. Awaiting payment outcome.", payload.orderId());
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_NAME));
    }

    @KafkaListener(topics = KafkaTopics.ORDER_PAYMENT, groupId = CONSUMER_NAME)
    public void onPaymentResult(String message, Acknowledgment acknowledgment) throws Exception {
        try {
            EventEnvelope<PaymentResultPayload> envelope = objectMapper.readValue(
                    message,
                    new TypeReference<>() {}
            );

            log.info("Received payment result for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());

            handlePaymentResult(envelope);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process payment result message: {}", message, e);
            throw e;
        }
    }

    @Transactional
    public void handlePaymentResult(EventEnvelope<PaymentResultPayload> envelope) {
        if (processedEventRepository.existsByEventIdAndConsumerName(envelope.eventId(), CONSUMER_NAME)) {
            log.info("Duplicate payment event ignored for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());
            return;
        }

        PaymentResultPayload payload = envelope.payload();
        Order order = orderRepository.findById(payload.orderId()).orElse(null);

        if (order != null) {
            if ("SUCCEEDED".equalsIgnoreCase(payload.status())) {
                order.setStatus(OrderStatus.CONFIRMED);
                orderRepository.save(order);
                log.info("Order status updated to CONFIRMED for order: {}", payload.orderId());
            } else if ("FAILED".equalsIgnoreCase(payload.status())) {
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
                log.info("Order status updated to CANCELLED on payment failure for order: {}", payload.orderId());
            }
        } else {
            log.warn("Order not found for payment outcome update: {}", payload.orderId());
        }

        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_NAME));
    }
}
