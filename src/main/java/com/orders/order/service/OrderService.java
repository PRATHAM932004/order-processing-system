package com.orders.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.entity.ReservationStatus;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.order.dto.CreateOrderRequest;
import com.orders.order.dto.OrderItemRequest;
import com.orders.order.dto.OrderItemResponse;
import com.orders.order.dto.OrderResponse;
import com.orders.order.entity.Customer;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderItem;
import com.orders.order.entity.OrderStatus;
import com.orders.order.entity.Product;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.repository.CustomerRepository;
import com.orders.order.repository.OrderRepository;
import com.orders.order.repository.ProductRepository;
import com.orders.outbox.OutboxEvent;
import com.orders.outbox.OutboxEventRepository;
import com.orders.payment.entity.Payment;
import com.orders.payment.entity.PaymentStatus;
import com.orders.payment.repository.PaymentRepository;
import com.orders.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
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

    @Autowired(required = false)
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired(required = false)
    private PaymentRepository paymentRepository;

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        AuthenticatedUser currentUser = getCurrentAuthenticatedUser();
        UUID targetCustomerId = request.getCustomerId();

        if (currentUser != null && currentUser.isInventoryManager()) {
            throw new AccessDeniedException("Inventory Managers cannot place customer orders");
        }

        if (currentUser != null && !currentUser.isAdmin()) {
            if (targetCustomerId != null && !targetCustomerId.equals(currentUser.id())) {
                throw new AccessDeniedException("Customers can only place orders for themselves");
            }
            targetCustomerId = currentUser.id();
        }

        log.info("Processing order placement for customer: {}", targetCustomerId);

        UUID finalCustomerId = targetCustomerId;
        Customer customer = customerRepository.findById(finalCustomerId)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + finalCustomerId));

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        List<OrderCreatedPayload.OrderItemPayload> eventItems = new ArrayList<>();
        Map<UUID, Product> productMap = new HashMap<>();

        for (OrderItemRequest itemRequest : request.getItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new NoSuchElementException("Product not found: " + itemRequest.getProductId()));
            productMap.put(product.getId(), product);

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

        Order order = new Order(customer.getId(), totalAmount, request.getPaymentMethod());
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

        return toResponse(savedOrder, customer, productMap);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + id));

        AuthenticatedUser currentUser = getCurrentAuthenticatedUser();
        if (currentUser != null && !currentUser.isAdmin() && !order.getCustomerId().equals(currentUser.id())) {
            throw new AccessDeniedException("You are not authorized to view this order");
        }

        return toResponse(order, null, null);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders(OrderStatus status, UUID customerId) {
        AuthenticatedUser currentUser = getCurrentAuthenticatedUser();
        UUID effectiveCustomerId = customerId;

        if (currentUser != null && !currentUser.isAdmin()) {
            effectiveCustomerId = currentUser.id();
        }

        List<Order> orders;
        if (effectiveCustomerId != null) {
            orders = orderRepository.findByCustomerIdWithItemsOrderByCreatedAtDesc(effectiveCustomerId);
        } else {
            orders = orderRepository.findAllWithItemsOrderByCreatedAtDesc();
        }

        if (status != null) {
            orders = orders.stream()
                    .filter(o -> o.getStatus() == status)
                    .toList();
        }

        return orders.stream()
                .map(order -> toResponse(order, null, null))
                .toList();
    }

    private AuthenticatedUser getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }

    private OrderResponse toResponse(Order order, Customer preloadedCustomer, Map<UUID, Product> preloadedProducts) {
        Customer customer = preloadedCustomer;
        if (customer == null && customerRepository != null && order.getCustomerId() != null) {
            customer = customerRepository.findById(order.getCustomerId()).orElse(null);
        }

        String customerName = customer != null ? customer.getName() : "Customer";
        String customerEmail = customer != null ? customer.getEmail() : "";

        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> {
                    Product product = preloadedProducts != null ? preloadedProducts.get(item.getProductId()) : null;
                    if (product == null && productRepository != null && item.getProductId() != null) {
                        product = productRepository.findById(item.getProductId()).orElse(null);
                    }
                    String productName = product != null ? product.getName() : "Product";
                    String category = product != null ? product.getCategory() : "General";
                    BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                    int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                    BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty));

                    return new OrderItemResponse(
                            item.getId(),
                            item.getProductId(),
                            productName,
                            category,
                            item.getQuantity(),
                            item.getUnitPrice(),
                            lineTotal
                    );
                })
                .toList();

        String currentPhase = resolvePhase(order);
        String failureReason = resolveFailureReason(order);

        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                customerName,
                customerEmail,
                order.getStatus(),
                currentPhase,
                failureReason,
                order.getTotalAmount(),
                itemResponses,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }

    private String resolvePhase(Order order) {
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return "ORDER_CONFIRMED";
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return "FAILED";
        }

        if (paymentRepository != null && order.getId() != null) {
            Optional<Payment> paymentOpt = paymentRepository.findByOrderId(order.getId());
            if (paymentOpt.isPresent()) {
                Payment payment = paymentOpt.get();
                if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
                    return "PAYMENT_PROCESSED";
                }
                if (payment.getStatus() == PaymentStatus.FAILED) {
                    return "FAILED";
                }
            }
        }

        if (inventoryReservationRepository != null && order.getId() != null) {
            List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderId(order.getId());
            if (!reservations.isEmpty()) {
                boolean anyFailed = reservations.stream()
                        .anyMatch(r -> r.getStatus() == ReservationStatus.FAILED || r.getStatus() == ReservationStatus.COMPENSATED);
                if (anyFailed) {
                    return "FAILED";
                }
                boolean anyReserved = reservations.stream()
                        .anyMatch(r -> r.getStatus() == ReservationStatus.RESERVED);
                if (anyReserved) {
                    return "INVENTORY_RESERVED";
                }
            }
        }

        return "ORDER_PLACED";
    }

    private String resolveFailureReason(Order order) {
        if (order.getStatus() != OrderStatus.CANCELLED) {
            return null;
        }

        if (paymentRepository != null && order.getId() != null) {
            Optional<Payment> paymentOpt = paymentRepository.findByOrderId(order.getId());
            if (paymentOpt.isPresent() && paymentOpt.get().getStatus() == PaymentStatus.FAILED) {
                return "Payment declined by payment gateway; reserved inventory stock compensated.";
            }
        }

        if (inventoryReservationRepository != null && order.getId() != null) {
            List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderId(order.getId());
            boolean failedInventory = reservations.stream()
                    .anyMatch(r -> r.getStatus() == ReservationStatus.FAILED);
            if (failedInventory) {
                return "Insufficient inventory stock available during reservation.";
            }
        }

        return "Order cancelled during asynchronous processing.";
    }
}
