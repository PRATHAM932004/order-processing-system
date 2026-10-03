package com.orders.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.order.dto.CreateOrderRequest;
import com.orders.order.dto.OrderItemRequest;
import com.orders.order.dto.OrderItemResponse;
import com.orders.order.dto.OrderResponse;
import com.orders.order.entity.Customer;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderItem;
import com.orders.order.entity.Product;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.repository.CustomerRepository;
import com.orders.order.repository.OrderRepository;
import com.orders.order.repository.ProductRepository;
import com.orders.outbox.OutboxEvent;
import com.orders.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        log.info("Processing order placement for customer: {}", request.getCustomerId());

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + request.getCustomerId()));

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        List<OrderCreatedPayload.OrderItemPayload> eventItems = new ArrayList<>();

        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new NoSuchElementException("Product not found: " + itemRequest.getProductId()));

            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            totalAmount = totalAmount.add(lineTotal);

            OrderItem orderItem = new OrderItem(product.getId(), itemRequest.getQuantity(), product.getPrice());
            orderItems.add(orderItem);

            eventItems.add(new OrderCreatedPayload.OrderItemPayload(
                    product.getId(),
                    itemRequest.getQuantity(),
                    product.getPrice()
            ));
        }

        Order order = new Order(customer.getId(), totalAmount);
        for (OrderItem item : orderItems) {
            order.addItem(item);
        }

        Order savedOrder = orderRepository.save(order);
        log.info("Order saved with ID: {} and status: {}", savedOrder.getId(), savedOrder.getStatus());

        // Construct Outbox event
        OrderCreatedPayload payload = new OrderCreatedPayload(
                savedOrder.getId(),
                customer.getId(),
                eventItems,
                savedOrder.getTotalAmount()
        );

        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelope.of("OrderCreated", payload);

        try {
            String jsonPayload = objectMapper.writeValueAsString(envelope);
            OutboxEvent outboxEvent = new OutboxEvent(
                    savedOrder.getId(),
                    "OrderCreated",
                    KafkaTopics.ORDER_CREATED,
                    jsonPayload
            );
            outboxEventRepository.save(outboxEvent);
            log.info("Outbox event created for order: {}", savedOrder.getId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderCreated event for order: {}", savedOrder.getId(), e);
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        return toResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + id));
        return toResponse(order);
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                itemResponses,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
