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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private InventoryService inventoryService;

    private UUID orderId;
    private UUID eventId;
    private UUID productAId;
    private UUID productBId;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        eventId = UUID.randomUUID();

        // Ensure distinct IDs
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        productAId = id1;
        productBId = id2;

        productA = new Product("Mouse", "Electronics", new BigDecimal("50.00"), 10);
        productA.setId(productAId);

        productB = new Product("Keyboard", "Electronics", new BigDecimal("100.00"), 5);
        productB.setId(productBId);
    }

    @Test
    @DisplayName("Should successfully reserve inventory and decrement stock when all items are available")
    void reserveInventory_whenAllStockAvailable_decrementsStockAndSavesReserved() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.CONSUMER_NAME))
                .thenReturn(false);
        when(productRepository.findByIdForUpdate(productAId)).thenReturn(Optional.of(productA));
        when(productRepository.findByIdForUpdate(productBId)).thenReturn(Optional.of(productB));

        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(
                        new OrderCreatedPayload.OrderItemPayload(productAId, 2, new BigDecimal("50.00")),
                        new OrderCreatedPayload.OrderItemPayload(productBId, 3, new BigDecimal("100.00"))
                ),
                new BigDecimal("400.00")
        );
        EventEnvelope<OrderCreatedPayload> envelope = EventEnvelope.of("OrderCreated", payload);

        InventoryResultPayload result = inventoryService.reserveInventory(
                new EventEnvelope<>(eventId, "OrderCreated", envelope.occurredAt(), payload)
        );

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("RESERVED");
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.failureReason()).isNull();

        // Verify stock decremented
        assertThat(productA.getStockQuantity()).isEqualTo(8);
        assertThat(productB.getStockQuantity()).isEqualTo(2);
        verify(productRepository).save(productA);
        verify(productRepository).save(productB);

        // Verify reservations persisted with status RESERVED
        ArgumentCaptor<InventoryReservation> captor = ArgumentCaptor.forClass(InventoryReservation.class);
        verify(inventoryReservationRepository, times(2)).save(captor.capture());
        List<InventoryReservation> savedReservations = captor.getAllValues();
        assertThat(savedReservations).allMatch(r -> r.getStatus() == ReservationStatus.RESERVED);

        // Verify idempotency record saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("BR-005: All-or-nothing reservation - if any item lacks stock, touch no stock and save FAILED")
    void reserveInventory_whenInsufficientStock_doesNotTouchStockAndSavesFailed() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.CONSUMER_NAME))
                .thenReturn(false);
        when(productRepository.findByIdForUpdate(productAId)).thenReturn(Optional.of(productA));
        when(productRepository.findByIdForUpdate(productBId)).thenReturn(Optional.of(productB));

        // Request 10 units of Product B which only has 5
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(
                        new OrderCreatedPayload.OrderItemPayload(productAId, 2, new BigDecimal("50.00")),
                        new OrderCreatedPayload.OrderItemPayload(productBId, 10, new BigDecimal("100.00"))
                ),
                new BigDecimal("1100.00")
        );

        InventoryResultPayload result = inventoryService.reserveInventory(
                new EventEnvelope<>(eventId, "OrderCreated", null, payload)
        );

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.failureReason()).contains("Insufficient stock");

        // Verify stock was NOT touched (all-or-nothing)
        assertThat(productA.getStockQuantity()).isEqualTo(10);
        assertThat(productB.getStockQuantity()).isEqualTo(5);
        verify(productRepository, never()).save(any(Product.class));

        // Verify FAILED reservation row persisted with quantity=0
        ArgumentCaptor<InventoryReservation> captor = ArgumentCaptor.forClass(InventoryReservation.class);
        verify(inventoryReservationRepository).save(captor.capture());
        InventoryReservation failedRes = captor.getValue();
        assertThat(failedRes.getStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(failedRes.getQuantity()).isEqualTo(0);
        assertThat(failedRes.getOrderId()).isEqualTo(orderId);

        // Verify idempotency record saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("BR-006: Deadlock-avoidance lock ordering - products must be locked in sorted UUID order")
    void reserveInventory_locksProductsInSortedIdOrder() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.CONSUMER_NAME))
                .thenReturn(false);
        when(productRepository.findByIdForUpdate(any())).thenReturn(Optional.of(productA));

        // Pass product IDs in reverse sorted order (productBId > productAId)
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(
                        new OrderCreatedPayload.OrderItemPayload(productBId, 1, new BigDecimal("100.00")),
                        new OrderCreatedPayload.OrderItemPayload(productAId, 1, new BigDecimal("50.00"))
                ),
                new BigDecimal("150.00")
        );

        inventoryService.reserveInventory(
                new EventEnvelope<>(eventId, "OrderCreated", null, payload)
        );

        // Verify that findByIdForUpdate was invoked in ASCENDING sorted order: productAId then productBId
        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).findByIdForUpdate(productAId);
        inOrder.verify(productRepository).findByIdForUpdate(productBId);
    }

    @Test
    @DisplayName("BR-007: Idempotency - duplicate event should return null without side effects")
    void reserveInventory_whenDuplicateEvent_skipsProcessing() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.CONSUMER_NAME))
                .thenReturn(true);

        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId,
                UUID.randomUUID(),
                List.of(new OrderCreatedPayload.OrderItemPayload(productAId, 1, new BigDecimal("50.00"))),
                new BigDecimal("50.00")
        );

        InventoryResultPayload result = inventoryService.reserveInventory(
                new EventEnvelope<>(eventId, "OrderCreated", null, payload)
        );

        assertThat(result).isNull();
        verify(productRepository, never()).findByIdForUpdate(any());
        verify(inventoryReservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("BR-010: Compensating transaction on PaymentFailed - restores stock and marks COMPENSATED")
    void compensateInventory_whenPaymentFailed_restoresStockAndMarksCompensated() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.COMPENSATION_CONSUMER_NAME))
                .thenReturn(false);

        InventoryReservation reservationA = new InventoryReservation(orderId, productAId, 3, ReservationStatus.RESERVED);
        when(inventoryReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED))
                .thenReturn(List.of(reservationA));
        when(productRepository.findByIdForUpdate(productAId)).thenReturn(Optional.of(productA));

        PaymentResultPayload payload = new PaymentResultPayload(
                orderId,
                "FAILED",
                new BigDecimal("150.00"),
                UUID.randomUUID()
        );

        inventoryService.compensateInventory(
                new EventEnvelope<>(eventId, "PaymentFailed", null, payload)
        );

        // Verify stock was restored (+3)
        assertThat(productA.getStockQuantity()).isEqualTo(13);
        verify(productRepository).save(productA);

        // Verify reservation marked as COMPENSATED
        assertThat(reservationA.getStatus()).isEqualTo(ReservationStatus.COMPENSATED);
        verify(inventoryReservationRepository).save(reservationA);

        // Verify idempotency record saved
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("BR-007: Idempotency on compensation - duplicate PaymentFailed event is ignored")
    void compensateInventory_whenDuplicateEvent_skipsCompensation() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, InventoryService.COMPENSATION_CONSUMER_NAME))
                .thenReturn(true);

        PaymentResultPayload payload = new PaymentResultPayload(
                orderId,
                "FAILED",
                new BigDecimal("150.00"),
                UUID.randomUUID()
        );

        inventoryService.compensateInventory(
                new EventEnvelope<>(eventId, "PaymentFailed", null, payload)
        );

        verify(inventoryReservationRepository, never()).findByOrderIdAndStatus(any(), any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }
}
