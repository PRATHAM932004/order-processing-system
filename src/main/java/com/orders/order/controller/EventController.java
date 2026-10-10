package com.orders.order.controller;

import com.orders.common.event.KafkaTopics;
import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.entity.ReservationStatus;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.order.dto.KafkaEventResponse;
import com.orders.order.entity.Order;
import com.orders.order.repository.OrderRepository;
import com.orders.outbox.OutboxEvent;
import com.orders.outbox.OutboxEventRepository;
import com.orders.payment.entity.Payment;
import com.orders.payment.entity.PaymentStatus;
import com.orders.payment.repository.PaymentRepository;
import com.orders.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final OutboxEventRepository outboxEventRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    @GetMapping
    public ResponseEntity<List<KafkaEventResponse>> getEvents(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String topic
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser currentUser = (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) ? u : null;

        Set<UUID> allowedOrderIds = null;
        if (currentUser != null && !currentUser.canManageInventory()) {
            allowedOrderIds = orderRepository.findByCustomerIdWithItemsOrderByCreatedAtDesc(currentUser.id())
                    .stream()
                    .map(Order::getId)
                    .collect(Collectors.toSet());
        }

        List<KafkaEventResponse> allEvents = new ArrayList<>();

        boolean isInventoryOnly = currentUser != null && currentUser.isInventoryManager();

        // 1. Outbox Events (OrderCreated on order.created)
        List<OutboxEvent> outboxEvents = isInventoryOnly
                ? List.of()
                : (orderId != null
                    ? outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(orderId)
                    : outboxEventRepository.findTop50ByOrderByCreatedAtDesc());

        long offsetCounter = 1000;
        for (OutboxEvent oe : outboxEvents) {
            if (allowedOrderIds != null && !allowedOrderIds.contains(oe.getAggregateId())) {
                continue;
            }
            Long latencyMs = (oe.getPublishedAt() != null && oe.getCreatedAt() != null)
                    ? Math.max(1L, Duration.between(oe.getCreatedAt(), oe.getPublishedAt()).toMillis())
                    : 12L;

            allEvents.add(new KafkaEventResponse(
                    oe.getId().toString(),
                    oe.getAggregateId(),
                    oe.getEventType(),
                    oe.getTopic(),
                    Math.abs(oe.getAggregateId().hashCode() % 3),
                    offsetCounter++,
                    oe.getPublishedAt() != null ? oe.getPublishedAt() : oe.getCreatedAt(),
                    "inventory-service",
                    oe.getPayload(),
                    latencyMs,
                    oe.getPublishedAt() != null ? "SUCCESS" : "IN_PROGRESS"
            ));
        }

        // 2. Inventory Reservations grouped per orderId (order.inventory)
        List<InventoryReservation> reservations = orderId != null
                ? inventoryReservationRepository.findByOrderId(orderId)
                : inventoryReservationRepository.findAll();

        Map<UUID, List<InventoryReservation>> byOrder = reservations.stream()
                .collect(Collectors.groupingBy(
                        InventoryReservation::getOrderId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<UUID, List<InventoryReservation>> entry : byOrder.entrySet()) {
            UUID ordId = entry.getKey();
            if (allowedOrderIds != null && !allowedOrderIds.contains(ordId)) {
                continue;
            }
            List<InventoryReservation> group = entry.getValue();
            InventoryReservation first = group.get(0);
            boolean anyFailed = group.stream().anyMatch(r -> r.getStatus() == ReservationStatus.FAILED);
            boolean anyCompensated = group.stream().anyMatch(r -> r.getStatus() == ReservationStatus.COMPENSATED);
            int totalQty = group.stream().mapToInt(InventoryReservation::getQuantity).sum();

            String evtType = anyFailed
                    ? "InventoryFailed"
                    : anyCompensated
                        ? "InventoryCompensated"
                        : "InventoryReserved";
            String status = anyFailed ? "FAILED" : "SUCCESS";
            String summary = String.format(
                    "{\"orderId\":\"%s\",\"lineItems\":%d,\"totalUnitsReserved\":%d,\"status\":\"%s\"}",
                    ordId, group.size(), totalQty, anyFailed ? "FAILED" : anyCompensated ? "COMPENSATED" : "RESERVED"
            );

            allEvents.add(new KafkaEventResponse(
                    first.getId().toString(),
                    ordId,
                    evtType,
                    KafkaTopics.ORDER_INVENTORY,
                    Math.abs(ordId.hashCode() % 3),
                    offsetCounter++,
                    first.getCreatedAt(),
                    "payment-service",
                    summary,
                    18L,
                    status
            ));
        }

        // 3. Payments (order.payment)
        List<Payment> payments = isInventoryOnly
                ? List.of()
                : (orderId != null
                    ? paymentRepository.findByOrderId(orderId).map(List::of).orElse(List.of())
                    : paymentRepository.findAll());

        for (Payment p : payments) {
            if (allowedOrderIds != null && !allowedOrderIds.contains(p.getOrderId())) {
                continue;
            }
            String evtType = p.getStatus() == PaymentStatus.SUCCEEDED ? "PaymentSucceeded" : "PaymentFailed";
            String status = p.getStatus() == PaymentStatus.SUCCEEDED ? "SUCCESS" : "FAILED";
            String summary = String.format(
                    "{\"orderId\":\"%s\",\"paymentId\":\"%s\",\"amount\":%s,\"method\":\"%s\",\"status\":\"%s\"}",
                    p.getOrderId(), p.getId(), p.getAmount(), p.getMethod(), p.getStatus()
            );

            allEvents.add(new KafkaEventResponse(
                    p.getId().toString(),
                    p.getOrderId(),
                    evtType,
                    KafkaTopics.ORDER_PAYMENT,
                    Math.abs(p.getOrderId().hashCode() % 3),
                    offsetCounter++,
                    p.getProcessedAt(),
                    "order-status-updater",
                    summary,
                    24L,
                    status
            ));
        }

        if (topic != null && !topic.isBlank() && !"ALL".equalsIgnoreCase(topic)) {
            allEvents = allEvents.stream()
                    .filter(e -> topic.equalsIgnoreCase(e.topic()))
                    .toList();
        }

        allEvents = allEvents.stream()
                .sorted(Comparator.comparing(KafkaEventResponse::timestamp, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(50)
                .toList();

        return ResponseEntity.ok(allEvents);
    }
}
