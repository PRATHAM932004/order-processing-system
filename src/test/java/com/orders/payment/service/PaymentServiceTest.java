package com.orders.payment.service;

import com.orders.common.event.EventEnvelope;
import com.orders.common.event.ProcessedEvent;
import com.orders.common.event.ProcessedEventRepository;
import com.orders.order.entity.Order;
import com.orders.order.event.InventoryResultPayload;
import com.orders.order.event.PaymentResultPayload;
import com.orders.order.repository.OrderRepository;
import com.orders.payment.entity.Payment;
import com.orders.payment.entity.PaymentMethod;
import com.orders.payment.entity.PaymentStatus;
import com.orders.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private PaymentService paymentService;

    private UUID orderId;
    private UUID eventId;
    private UUID normalCustomerId;
    private Order normalOrder;
    private Order failOrder;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        normalCustomerId = UUID.fromString("a0000000-0000-0000-0000-000000000001");

        normalOrder = new Order(normalCustomerId, new BigDecimal("1598.00"));
        normalOrder.setId(orderId);

        failOrder = new Order(PaymentService.TEST_FAIL_CUSTOMER_ID, new BigDecimal("3499.00"));
        failOrder.setId(orderId);
    }

    @Test
    @DisplayName("Should successfully process payment for normal customer with SUCCEEDED status")
    void processPayment_whenNormalCustomer_savesSucceededPaymentAndReturnsPayload() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, PaymentService.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(normalOrder));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(eventId, "InventoryReserved", null, inventoryPayload);

        PaymentResultPayload result = paymentService.processPayment(envelope);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.amount()).isEqualTo(new BigDecimal("1598.00"));
        assertThat(result.paymentId()).isNotNull();

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(savedPayment.getMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(savedPayment.getAmount()).isEqualTo(new BigDecimal("1598.00"));

        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("BR-009: Should deterministically simulate payment failure for designated test customer")
    void processPayment_whenTestFailCustomer_savesFailedPaymentAndReturnsPayload() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, PaymentService.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(failOrder));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(eventId, "InventoryReserved", null, inventoryPayload);

        PaymentResultPayload result = paymentService.processPayment(envelope);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.amount()).isEqualTo(new BigDecimal("3499.00"));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        Payment savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(savedPayment.getMethod()).isEqualTo(PaymentMethod.MOCK);

        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("BR-007: Should skip processing when event is already recorded in processed_events (Idempotency)")
    void processPayment_whenDuplicateEvent_skipsProcessing() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, PaymentService.CONSUMER_NAME))
                .thenReturn(true);

        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(eventId, "InventoryReserved", null, inventoryPayload);

        PaymentResultPayload result = paymentService.processPayment(envelope);

        assertThat(result).isNull();
        verify(orderRepository, never()).findById(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should return null and log warning when order is not found in database")
    void processPayment_whenOrderNotFound_returnsNull() {
        when(processedEventRepository.existsByEventIdAndConsumerName(eventId, PaymentService.CONSUMER_NAME))
                .thenReturn(false);
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        InventoryResultPayload inventoryPayload = new InventoryResultPayload(orderId, "RESERVED", null);
        EventEnvelope<InventoryResultPayload> envelope = new EventEnvelope<>(eventId, "InventoryReserved", null, inventoryPayload);

        PaymentResultPayload result = paymentService.processPayment(envelope);

        assertThat(result).isNull();
        verify(paymentRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }
}
