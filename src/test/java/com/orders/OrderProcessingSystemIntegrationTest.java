package com.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.EventEnvelope;
import com.orders.common.event.KafkaTopics;
import com.orders.common.event.ProcessedEventRepository;
import com.orders.inventory.entity.InventoryReservation;
import com.orders.inventory.entity.ReservationStatus;
import com.orders.inventory.repository.InventoryReservationRepository;
import com.orders.inventory.service.InventoryService;
import com.orders.order.dto.CreateOrderRequest;
import com.orders.order.dto.OrderItemRequest;
import com.orders.order.dto.OrderResponse;
import com.orders.order.entity.Customer;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderStatus;
import com.orders.order.entity.Product;
import com.orders.order.event.OrderCreatedPayload;
import com.orders.order.repository.CustomerRepository;
import com.orders.order.repository.OrderItemRepository;
import com.orders.order.repository.OrderRepository;
import com.orders.order.repository.ProductRepository;
import com.orders.order.service.OrderService;
import com.orders.outbox.OutboxEventRepository;
import com.orders.payment.entity.Payment;
import com.orders.payment.entity.PaymentStatus;
import com.orders.payment.repository.PaymentRepository;
import com.orders.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "orders.outbox.poll-interval-ms=200",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class OrderProcessingSystemIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.execute("DELETE FROM notifications");
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        customerRepository.deleteAll();
    }

    private void authenticateAs(Customer customer) {
        AuthenticatedUser user = new AuthenticatedUser(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getRole()
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + customer.getRole())))
        );
    }

    @Test
    @DisplayName("Scenario 1: Full happy path - order confirmed, stock decremented, payment succeeded")
    void shouldProcessOrderThroughFullHappyPath() {
        Customer customer = customerRepository.save(new Customer("Alice Johnson", "alice.johnson@example.com"));
        Product product = productRepository.save(new Product("Gaming Mouse", "Peripherals", new BigDecimal("50.00"), 10));

        authenticateAs(customer);

        CreateOrderRequest request = new CreateOrderRequest(
                customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 2)),
                "UPI"
        );

        OrderResponse placedOrder = orderService.placeOrder(request);
        UUID orderId = placedOrder.id();

        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order order = orderRepository.findById(orderId).orElseThrow();
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

                    Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
                    assertThat(updatedProduct.getStockQuantity()).isEqualTo(8);

                    List<Payment> payments = paymentRepository.findAll();
                    assertThat(payments).hasSize(1);
                    Payment payment = payments.get(0);
                    assertThat(payment.getOrderId()).isEqualTo(orderId);
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
                    assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));

                    List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
                    assertThat(reservations).hasSize(1);
                    assertThat(reservations.get(0).getQuantity()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("Scenario 2: Insufficient stock - reservation fails, order cancelled, zero payment")
    void shouldCancelOrderOnInsufficientStockWithoutPayment() {
        Customer customer = customerRepository.save(new Customer("Bob Smith", "bob.smith@example.com"));
        Product product = productRepository.save(new Product("Ultra-wide Monitor", "Electronics", new BigDecimal("400.00"), 1));

        authenticateAs(customer);

        CreateOrderRequest request = new CreateOrderRequest(
                customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 5)),
                "UPI"
        );

        OrderResponse placedOrder = orderService.placeOrder(request);
        UUID orderId = placedOrder.id();

        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order order = orderRepository.findById(orderId).orElseThrow();
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

                    Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
                    assertThat(updatedProduct.getStockQuantity()).isEqualTo(1);

                    List<Payment> payments = paymentRepository.findAll();
                    assertThat(payments).isEmpty();

                    List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.FAILED);
                    assertThat(reservations).hasSize(1);
                    assertThat(reservations.get(0).getQuantity()).isEqualTo(0);
                });
    }

    @Test
    @DisplayName("Scenario 3: Payment failure with stock restored - compensation releases stock, order cancelled")
    void shouldCompensateInventoryAndCancelOrderOnPaymentFailure() {
        Customer customer = customerRepository.save(new Customer("Charlie Brown", "charlie.brown@example.com"));
        Product product = productRepository.save(new Product("Mechanical Keyboard", "Peripherals", new BigDecimal("75.00"), 10));

        authenticateAs(customer);

        CreateOrderRequest request = new CreateOrderRequest(
                customer.getId(),
                List.of(new OrderItemRequest(product.getId(), 3)),
                "MOCK_FAIL"
        );

        OrderResponse placedOrder = orderService.placeOrder(request);
        UUID orderId = placedOrder.id();

        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order order = orderRepository.findById(orderId).orElseThrow();
                    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

                    Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
                    assertThat(updatedProduct.getStockQuantity()).isEqualTo(10);

                    List<Payment> payments = paymentRepository.findAll();
                    assertThat(payments).hasSize(1);
                    Payment payment = payments.get(0);
                    assertThat(payment.getOrderId()).isEqualTo(orderId);
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

                    List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.COMPENSATED);
                    assertThat(reservations).hasSize(1);
                    assertThat(reservations.get(0).getQuantity()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("Scenario 4: Idempotency ledger - same event published twice produces single side effect")
    void shouldPreventDuplicateSideEffectsWhenEventPublishedTwice() throws Exception {
        Customer customer = customerRepository.save(new Customer("Diana Prince", "diana.prince@example.com"));
        Product product = productRepository.save(new Product("Noise Cancelling Headphones", "Audio", new BigDecimal("150.00"), 20));

        Order order = new Order(customer.getId(), new BigDecimal("300.00"), "UPI");
        order = orderRepository.save(order);

        UUID fixedEventId = UUID.randomUUID();
        OrderCreatedPayload.OrderItemPayload itemPayload = new OrderCreatedPayload.OrderItemPayload(
                product.getId(),
                2,
                new BigDecimal("150.00")
        );
        OrderCreatedPayload payload = new OrderCreatedPayload(
                order.getId(),
                customer.getId(),
                List.of(itemPayload),
                new BigDecimal("300.00")
        );
        EventEnvelope<OrderCreatedPayload> envelope = new EventEnvelope<>(
                fixedEventId,
                "OrderCreated",
                Instant.now(),
                payload
        );
        String eventPayloadJson = objectMapper.writeValueAsString(envelope);

        // 1st Publish to real Kafka topic
        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, order.getId().toString(), eventPayloadJson)
                .get(10, TimeUnit.SECONDS);

        // Await 1st processing
        UUID orderId = order.getId();
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Product p = productRepository.findById(product.getId()).orElseThrow();
                    assertThat(p.getStockQuantity()).isEqualTo(18);

                    List<InventoryReservation> reservations = inventoryReservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);
                    assertThat(reservations).hasSize(1);

                    boolean recordedInLedger = processedEventRepository.existsByEventIdAndConsumerName(fixedEventId, InventoryService.CONSUMER_NAME);
                    assertThat(recordedInLedger).isTrue();
                });

        // 2nd Publish of identical event (same eventId, same payload)
        kafkaTemplate.send(KafkaTopics.ORDER_CREATED, order.getId().toString(), eventPayloadJson)
                .get(10, TimeUnit.SECONDS);

        // Allow time for Kafka consumer to receive the duplicate message
        Thread.sleep(1500);

        // Verify that NO second side effect took place
        Product p = productRepository.findById(product.getId()).orElseThrow();
        assertThat(p.getStockQuantity()).isEqualTo(18);

        List<InventoryReservation> allReservations = inventoryReservationRepository.findAll();
        assertThat(allReservations)
                .as("Only a single reservation row should exist despite duplicate Kafka event")
                .hasSize(1);

        assertThat(processedEventRepository.existsByEventIdAndConsumerName(fixedEventId, InventoryService.CONSUMER_NAME))
                .isTrue();
    }
}
