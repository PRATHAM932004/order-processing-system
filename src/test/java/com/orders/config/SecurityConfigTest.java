package com.orders.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.auth.AuthController;
import com.orders.auth.service.AuthService;
import com.orders.order.controller.CustomerController;
import com.orders.order.controller.EventController;
import com.orders.order.controller.OrderController;
import com.orders.order.controller.ProductController;
import com.orders.order.controller.SystemStatusController;
import com.orders.order.dto.CreateOrderRequest;
import com.orders.order.dto.CustomerResponse;
import com.orders.order.dto.InventoryReservationResponse;
import com.orders.order.dto.OrderItemRequest;
import com.orders.order.dto.OrderResponse;
import com.orders.order.dto.ProductRequest;
import com.orders.order.dto.ProductResponse;
import com.orders.order.entity.OrderStatus;
import com.orders.order.service.CustomerService;
import com.orders.order.service.EventService;
import com.orders.order.service.OrderService;
import com.orders.order.service.ProductService;
import com.orders.order.service.SystemStatusService;
import com.orders.security.JwtAuthenticationFilter;
import com.orders.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        OrderController.class,
        CustomerController.class,
        ProductController.class,
        AuthController.class,
        EventController.class,
        SystemStatusController.class
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private CustomerService customerService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private SystemStatusService systemStatusService;

    // --- Unauthenticated caller tests ---

    @Test
    @DisplayName("Unauthenticated caller POST /api/orders returns 401 Unauthorized")
    void placeOrder_unauthenticated_returns401() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                List.of(new OrderItemRequest(UUID.randomUUID(), 1))
        );

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Unauthenticated caller GET /api/customers returns 401 Unauthorized")
    void getCustomers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("Unauthenticated caller GET /api/products returns 200 OK (public catalog browsing)")
    void getProducts_unauthenticated_returns200() throws Exception {
        when(productService.getAllProducts(null)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Unauthenticated caller POST /api/products returns 401 Unauthorized")
    void createProduct_unauthenticated_returns401() throws Exception {
        ProductRequest request = new ProductRequest("Keyboard", "Electronics", new BigDecimal("1299.00"), 10);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Unauthenticated caller GET /api/products/reservations returns 401 Unauthorized")
    void getReservations_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/products/reservations"))
                .andExpect(status().isUnauthorized());
    }

    // --- Role: CUSTOMER tests ---

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("Customer POST /api/orders returns 201 Created")
    void placeOrder_asCustomer_returns201() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(new OrderItemRequest(UUID.randomUUID(), 1))
        );

        OrderResponse response = new OrderResponse(
                orderId,
                customerId,
                OrderStatus.PENDING,
                new BigDecimal("799.00"),
                Collections.emptyList(),
                Instant.now(),
                Instant.now()
        );

        when(orderService.placeOrder(any(CreateOrderRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId.toString()));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("Customer GET /api/customers returns 403 Forbidden")
    void getCustomers_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("Customer POST /api/products returns 403 Forbidden")
    void createProduct_asCustomer_returns403() throws Exception {
        ProductRequest request = new ProductRequest("Keyboard", "Electronics", new BigDecimal("1299.00"), 10);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    @DisplayName("Customer GET /api/products/reservations returns 403 Forbidden")
    void getReservations_asCustomer_returns403() throws Exception {
        mockMvc.perform(get("/api/products/reservations"))
                .andExpect(status().isForbidden());
    }

    // --- Role: INVENTORY_MANAGER tests ---

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("Inventory Manager POST /api/orders returns 403 Forbidden")
    void placeOrder_asInventoryManager_returns403() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                UUID.randomUUID(),
                List.of(new OrderItemRequest(UUID.randomUUID(), 1))
        );

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("Inventory Manager POST /api/products returns 201 Created")
    void createProduct_asInventoryManager_returns201() throws Exception {
        ProductRequest request = new ProductRequest("Monitor", "Electronics", new BigDecimal("9999.00"), 5);
        ProductResponse response = new ProductResponse(UUID.randomUUID(), "Monitor", "Electronics", new BigDecimal("9999.00"), 5);

        when(productService.createProduct(any(ProductRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Monitor"));
    }

    @Test
    @WithMockUser(roles = "INVENTORY_MANAGER")
    @DisplayName("Inventory Manager GET /api/products/reservations returns 200 OK")
    void getReservations_asInventoryManager_returns200() throws Exception {
        when(productService.getAllReservations()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/products/reservations"))
                .andExpect(status().isOk());
    }

    // --- Role: ADMIN tests ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin GET /api/customers returns 200 OK")
    void getCustomers_asAdmin_returns200() throws Exception {
        when(customerService.getAllCustomers()).thenReturn(List.of(
                new CustomerResponse(UUID.randomUUID(), "Admin", "admin@order.com", "ADMIN", Instant.now())
        ));

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin POST /api/orders returns 201 Created")
    void placeOrder_asAdmin_returns201() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(new OrderItemRequest(UUID.randomUUID(), 1))
        );

        OrderResponse response = new OrderResponse(
                orderId,
                customerId,
                OrderStatus.PENDING,
                new BigDecimal("799.00"),
                Collections.emptyList(),
                Instant.now(),
                Instant.now()
        );

        when(orderService.placeOrder(any(CreateOrderRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId.toString()));
    }
}
