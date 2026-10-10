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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    public static final String CONSUMER_NAME = "payment-service";

    // Designated test customer ID for deterministic payment failure simulation (BR-009, ADR-007)
    public static final UUID TEST_FAIL_CUSTOMER_ID = UUID.fromString("a0000000-0000-0000-0000-000000000002");

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public PaymentResultPayload processPayment(EventEnvelope<InventoryResultPayload> envelope) {
        if (processedEventRepository.existsByEventIdAndConsumerName(envelope.eventId(), CONSUMER_NAME)) {
            log.info("Duplicate InventoryResult event ignored for payment for order: {}, eventId: {}",
                    envelope.payload().orderId(), envelope.eventId());
            return null;
        }

        UUID orderId = envelope.payload().orderId();
        Order order = orderRepository.findById(orderId).orElse(null);

        if (order == null) {
            log.warn("Order not found for payment processing: {}", orderId);
            return null;
        }

        // BR-009: Deterministic mock payment evaluation (via paymentMethod = MOCK_FAIL/MOCK or TEST_FAIL_CUSTOMER_ID)
        PaymentStatus status;
        PaymentMethod method;

        String requestedMethod = order.getPaymentMethod();
        boolean isSimulatedFailure = TEST_FAIL_CUSTOMER_ID.equals(order.getCustomerId())
                || "MOCK_FAIL".equalsIgnoreCase(requestedMethod)
                || "MOCK".equalsIgnoreCase(requestedMethod);

        if (isSimulatedFailure) {
            log.info("Simulating payment failure for order: {} (customer: {}, paymentMethod: {})",
                    orderId, order.getCustomerId(), requestedMethod);
            status = PaymentStatus.FAILED;
            method = PaymentMethod.MOCK;
        } else {
            log.info("Processing successful payment for customer: {} on order: {} using method: {}",
                    order.getCustomerId(), orderId, requestedMethod);
            status = PaymentStatus.SUCCEEDED;
            method = resolvePaymentMethod(requestedMethod);
        }

        Payment payment = new Payment(orderId, status, order.getTotalAmount(), method);
        Payment savedPayment = paymentRepository.save(payment);

        // BR-007: Record idempotency ledger in the same database transaction
        processedEventRepository.save(new ProcessedEvent(envelope.eventId(), CONSUMER_NAME));

        log.info("Payment record created with ID: {}, status: {} for order: {}",
                savedPayment.getId(), status, orderId);

        return new PaymentResultPayload(
                orderId,
                status.name(),
                order.getTotalAmount(),
                savedPayment.getId()
        );
    }

    private PaymentMethod resolvePaymentMethod(String requestedMethod) {
        if (requestedMethod == null || requestedMethod.isBlank()) {
            return PaymentMethod.UPI;
        }
        try {
            return PaymentMethod.valueOf(requestedMethod.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PaymentMethod.UPI;
        }
    }
}
