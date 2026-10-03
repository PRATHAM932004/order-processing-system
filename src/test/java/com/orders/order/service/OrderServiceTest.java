package com.orders.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.event.KafkaTopics;
import com.orders.order.dto.CreateOrderRequest;
import com.orders.order.dto.OrderItemRequest;
import com.orders.order.dto.OrderResponse;
import com.orders.order.entity.Customer;
import com.orders.order.entity.Order;
import com.orders.order.entity.OrderStatus;
import com.orders.order.entity.Product;
import com.orders.order.repository.CustomerRepository;
import com.orders.order.repository.OrderRepository;
import com.orders.order.repository.ProductRepository;
import com.orders.outbox.OutboxEvent;
import com.orders.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private OrderService orderService;

    private UUID customerId;
    private UUID product1Id;
    private UUID product2Id;
    private Customer customer;
    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        product1Id = UUID.randomUUID();
        product2Id = UUID.randomUUID();

        customer = new Customer("Alice Johnson", "alice@example.com");
        customer.setId(customerId);

        product1 = new Product("Wireless Mouse", "Electronics", new BigDecimal("799.00"), 100);
        product1.setId(product1Id);

        product2 = new Product("Mechanical Keyboard", "Electronics", new BigDecimal("3499.00"), 50);
        product2.setId(product2Id);
    }

    @Test
    @DisplayName("placeOrder computes server-side total and atomically creates order and outbox event")
    void placeOrder_calculatesTotalAndSavesOrderAndOutboxEvent() {
        // Given: 2x product1 (799.00 * 2 = 1598.00) + 1x product2 (3499.00 * 1 = 3499.00) -> 5097.00
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(
                        new OrderItemRequest(product1Id, 2),
                        new OrderItemRequest(product2Id, 1)
                )
        );

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(productRepository.findById(product1Id)).thenReturn(Optional.of(product1));
        when(productRepository.findById(product2Id)).thenReturn(Optional.of(product2));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order orderToSave = invocation.getArgument(0);
            orderToSave.setId(UUID.randomUUID());
            return orderToSave;
        });

        // When
        OrderResponse response = orderService.placeOrder(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("5097.00"));
        assertThat(response.items()).hasSize(2);

        // Verify outbox write
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());

        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getAggregateId()).isEqualTo(response.id());
        assertThat(outboxEvent.getEventType()).isEqualTo("OrderCreated");
        assertThat(outboxEvent.getTopic()).isEqualTo(KafkaTopics.ORDER_CREATED);
        assertThat(outboxEvent.getPayload()).contains("OrderCreated");
        assertThat(outboxEvent.getPayload()).contains("5097");
    }

    @Test
    @DisplayName("placeOrder throws NoSuchElementException when customer does not exist")
    void placeOrder_throwsException_whenCustomerNotFound() {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(new OrderItemRequest(product1Id, 1))
        );

        when(customerRepository.findById(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    @DisplayName("placeOrder throws NoSuchElementException when product does not exist")
    void placeOrder_throwsException_whenProductNotFound() {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(new OrderItemRequest(product1Id, 1))
        );

        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));
        when(productRepository.findById(product1Id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.placeOrder(request))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    @DisplayName("getOrderById returns order response when order exists")
    void getOrderById_success() {
        UUID orderId = UUID.randomUUID();
        Order order = new Order(customerId, new BigDecimal("799.00"));
        order.setId(orderId);

        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById(orderId);

        assertThat(response.id()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("getOrderById throws NoSuchElementException when order does not exist")
    void getOrderById_notFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdWithItems(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrderById(orderId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Order not found");
    }
}
