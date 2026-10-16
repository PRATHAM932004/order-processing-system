package com.orders.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.common.error.GlobalExceptionHandler;
import com.orders.order.dto.CreateUserRequest;
import com.orders.order.dto.CustomerResponse;
import com.orders.order.dto.UpdateUserRoleRequest;
import com.orders.order.service.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CustomerControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private CustomerController customerController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(customerController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/customers returns 200 OK with customer responses from CustomerService")
    void getAllCustomers_returnsList() throws Exception {
        CustomerResponse c1 = new CustomerResponse(
                UUID.randomUUID(), "Admin", "admin@order.com", "ADMIN", Instant.now()
        );
        CustomerResponse c2 = new CustomerResponse(
                UUID.randomUUID(), "Alice", "alice@order.com", "CUSTOMER", Instant.now()
        );

        when(customerService.getAllCustomers()).thenReturn(List.of(c1, c2));

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value("admin@order.com"))
                .andExpect(jsonPath("$[1].email").value("alice@order.com"));
    }

    @Test
    @DisplayName("GET /api/customers returns 403 Forbidden when CustomerService throws AccessDeniedException")
    void getAllCustomers_forbidden() throws Exception {
        when(customerService.getAllCustomers())
                .thenThrow(new AccessDeniedException("Only ADMIN users can list all customers"));

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only ADMIN users can list all customers"));
    }

    @Test
    @DisplayName("POST /api/customers returns 201 Created when CustomerService creates account")
    void createUser_success() throws Exception {
        CreateUserRequest request = new CreateUserRequest(
                "Warehouse Lead",
                "warehouse.lead@order.com",
                "password123",
                "INVENTORY_MANAGER"
        );

        UUID createdId = UUID.randomUUID();
        CustomerResponse response = new CustomerResponse(
                createdId,
                "Warehouse Lead",
                "warehouse.lead@order.com",
                "INVENTORY_MANAGER",
                Instant.now()
        );

        when(customerService.createUser(any(CreateUserRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(createdId.toString()))
                .andExpect(jsonPath("$.name").value("Warehouse Lead"))
                .andExpect(jsonPath("$.email").value("warehouse.lead@order.com"))
                .andExpect(jsonPath("$.role").value("INVENTORY_MANAGER"));
    }

    @Test
    @DisplayName("POST /api/customers returns 409 Conflict when email already exists")
    void createUser_duplicateEmail_returnsConflict() throws Exception {
        CreateUserRequest request = new CreateUserRequest(
                "Duplicate User",
                "existing@order.com",
                "password123",
                "CUSTOMER"
        );

        when(customerService.createUser(any(CreateUserRequest.class)))
                .thenThrow(new IllegalStateException("Email is already registered: existing@order.com"));

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email is already registered: existing@order.com"));
    }

    @Test
    @DisplayName("PATCH /api/customers/{id}/role returns 200 OK with updated role")
    void updateUserRole_success() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserRoleRequest request = new UpdateUserRoleRequest("INVENTORY_MANAGER");
        CustomerResponse response = new CustomerResponse(
                userId, "Bob", "bob@order.com", "INVENTORY_MANAGER", Instant.now()
        );

        when(customerService.updateUserRole(eq(userId), any(UpdateUserRoleRequest.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/customers/{id}/role", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("INVENTORY_MANAGER"));
    }

    @Test
    @DisplayName("PATCH /api/customers/{id}/role returns 400 Bad Request when demoting primary admin")
    void updateUserRole_demotePrimaryAdmin_returnsBadRequest() throws Exception {
        UUID adminId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
        UpdateUserRoleRequest request = new UpdateUserRoleRequest("CUSTOMER");

        when(customerService.updateUserRole(eq(adminId), any(UpdateUserRoleRequest.class)))
                .thenThrow(new IllegalArgumentException("Cannot demote the primary system administrator (admin@order.com)"));

        mockMvc.perform(patch("/api/customers/{id}/role", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot demote the primary system administrator (admin@order.com)"));
    }
}
