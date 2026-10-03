package com.orders.inventory.service;

import com.orders.common.event.EventEnvelope;
import com.orders.common.event.ProcessedEvent;
import com.orders.common.event.ProcessedEventRepository;
import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.entity.ReservationStatus;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.order.entity.Product;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.order.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    public static final String CONSUMER_NAME = "inventory-service";
    public static final String COMPENSATION_CONSUMER_NAME = "inventory-compensation";

    private final ProductRepository productRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public InventoryResultPayload reserveInventory(EventEnvelope<OrderCreatedPayload> envelope) {
        if (processedEventRepository.existsByEventIdAndConsumerName(envelope.eventId(), CONSUMER_NAME)) {
            log.info("Duplicate OrderCreated event ignored for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());
            return null;
        }

        OrderCreatedPayload payload = envelope.payload();
        UUID orderId = payload.orderId();
        List<OrderCreatedPayload.OrderItemPayload> items = payload.items();

        // BR-006: Deadlock-Avoidance Lock Ordering (sort product IDs before acquiring locks)
        List<UUID> sortedProductIds = items.stream()
                .map(OrderCreatedPayload.OrderItemPayload::productId)
                .distinct()
                .sorted()
                .toList();

        Map<UUID, Product> lockedProducts = new LinkedHashMap<>();
        for (UUID productId : sortedProductIds) {
            Product product = productRepository.findByIdForUpdate(productId).orElse(null);
            if (product != null) {
                lockedProducts.put(productId, product);
            }
        }

        // Aggregate quantities in case order has duplicate items for same product
        Map<UUID, Integer> requestedQuantities = new HashMap<>();
        for (OrderCreatedPayload.OrderItemPayload item : items) {
            requestedQuantities.merge(item.productId(), item.quantity(), Integer::sum);
        }

        // BR-005: All-or-Nothing Inventory Reservation
        boolean allAvailable = true;
        UUID failedProductId = null;
        String failureReason = null;

        for (Map.Entry<UUID, Integer> entry : requestedQuantities.entrySet()) {
            UUID productId = entry.getKey();
            int requiredQty = entry.getValue();
            Product product = lockedProducts.get(productId);

            if (product == null) {
                allAvailable = false;
                failedProductId = productId;
                failureReason = "Product not found: " + productId;
                break;
            } else if (product.getStockQuantity() < requiredQty) {
                allAvailable = false;
                failedProductId = productId;
                failureReason = "Insufficient stock for product " + productId +
                        ": requested=" + requiredQty + ", available=" + product.getStockQuantity();
                break;
            }
        }

        InventoryResultPayload result;

        if (allAvailable) {
            // Decrement stock for all items
            for (Map.Entry<UUID, Integer> entry : requestedQuantities.entrySet()) {
                Product product = lockedProducts.get(entry.getKey());
                product.setStockQuantity(product.getStockQuantity() - entry.getValue());
                productRepository.save(product);
            }

            // Persist RESERVED inventory reservations
            for (OrderCreatedPayload.OrderItemPayload item : items) {
                InventoryReservation reservation = new InventoryReservation(
                        orderId,
                        item.productId(),
                        item.quantity(),
                        ReservationStatus.RESERVED
                );
                inventoryReservationRepository.save(reservation);
            }

            log.info("Inventory successfully reserved for order: {}", orderId);
            result = new InventoryResultPayload(orderId, "RESERVED", null);
        } else {
            // BR-005: Touch no stock on failure; persist FAILED reservation row with quantity=0
            UUID refProductId = (failedProductId != null) ? failedProductId : items.get(0).productId();
            InventoryReservation failedReservation = new InventoryReservation(
                    orderId,
                    refProductId,
                    0,
                    ReservationStatus.FAILED
            );
            inventoryReservationRepository.save(failedReservation);

            log.warn("Inventory reservation failed for order: {}. Reason: {}", orderId, failureReason);
            result = new InventoryResultPayload(orderId, "FAILED", failureReason);
        }

        // BR-007: Record idempotency ledger in same transaction
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_NAME));

        return result;
    }

    @Transactional
    public void compensateInventory(EventEnvelope<PaymentResultPayload> envelope) {
        if (processedEventRepository.existsByEventIdAndConsumerName(envelope.eventId(), COMPENSATION_CONSUMER_NAME)) {
            log.info("Duplicate PaymentFailed event ignored for compensation for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());
            return;
        }

        UUID orderId = envelope.payload().orderId();
        List<InventoryReservation> activeReservations = inventoryReservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);

        if (activeReservations.isEmpty()) {
            log.info("No active RESERVED inventory to compensate for order: {}", orderId);
            processedEventRepository.save(new ProcessedEvent(envelope.eventId(), COMPENSATION_CONSUMER_NAME));
            return;
        }

        // BR-006 & BR-010: Lock products in sorted order and release reserved stock
        List<UUID> sortedProductIds = activeReservations.stream()
                .map(InventoryReservation::getProductId)
                .distinct()
                .sorted()
                .toList();

        Map<UUID, Product> lockedProducts = new LinkedHashMap<>();
        for (UUID productId : sortedProductIds) {
            Product product = productRepository.findByIdForUpdate(productId).orElse(null);
            if (product != null) {
                lockedProducts.put(productId, product);
            }
        }

        for (InventoryReservation reservation : activeReservations) {
            Product product = lockedProducts.get(reservation.getProductId());
            if (product != null) {
                product.setStockQuantity(product.getStockQuantity() + reservation.getQuantity());
                productRepository.save(product);
                log.info("Restored {} units to product: {} for order: {}",
                        reservation.getQuantity(), reservation.getProductId(), orderId);
            }
            reservation.setStatus(ReservationStatus.COMPENSATED);
            inventoryReservationRepository.save(reservation);
        }

        log.info("Compensating stock release completed for order: {}", orderId);
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), COMPENSATION_CONSUMER_NAME));
    }
}
