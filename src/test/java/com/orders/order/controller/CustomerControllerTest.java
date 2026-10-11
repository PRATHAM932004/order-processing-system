package com.orders.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orders.order.dto.CreateUserRequest;
import com.orders.order.dto.UpdateUserRoleRequest;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CustomerController customerController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(customerController).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(),
                "Test " + role,
                role.toLowerCase() + "@order.com",
                role
        );
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("GET /api/customers returns 200 OK with all customers when authenticated as ADMIN")
    void getAllCustomers_asAdmin_returnsList() throws Exception {
        authenticateAs("ADMIN");

        Customer c1 = new Customer("Admin", "admin@order.com", "hash1", "ADMIN");
        c1.setId(UUID.randomUUID());
        c1.setCreatedAt(Instant.now());

        Customer c2 = new Customer("Alice", "alice@order.com", "hash2", "CUSTOMER");
        c2.setId(UUID.randomUUID());
        c2.setCreatedAt(Instant.now().minusSeconds(60));

        when(customerRepository.findAll()).thenReturn(List.of(c2, c1));

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value("admin@order.com"))
                .andExpect(jsonPath("$[1].email").value("alice@order.com"));
    }

    @Test
    @DisplayName("GET /api/customers returns 403 Forbidden when authenticated as CUSTOMER")
    void getAllCustomers_asCustomer_returnsForbidden() throws Exception {
        authenticateAs("CUSTOMER");

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Only ADMIN users can list all customers"));
    }

    @Test
    @DisplayName("POST /api/customers creates new staff account when authenticated as ADMIN")
    void createUser_asAdmin_success() throws Exception {
        authenticateAs("ADMIN");

        CreateUserRequest request = new CreateUserRequest(
                "Warehouse Lead",
                "warehouse.lead@order.com",
                "password123",
                "INVENTORY_MANAGER"
        );

        UUID createdId = UUID.randomUUID();
        when(customerRepository.findByEmailIgnoreCase("warehouse.lead@order.com"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("encoded_hash");
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer saved = inv.getArgument(0);
            saved.setId(createdId);
            return saved;
        });

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
        authenticateAs("ADMIN");

        CreateUserRequest request = new CreateUserRequest(
                "Duplicate User",
                "existing@order.com",
                "password123",
                "CUSTOMER"
        );

        when(customerRepository.findByEmailIgnoreCase("existing@order.com"))
                .thenReturn(Optional.of(new Customer("Existing", "existing@order.com")));

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email is already registered: existing@order.com"));
    }

    @Test
    @DisplayName("PATCH /api/customers/{id}/role updates role between CUSTOMER, INVENTORY_MANAGER, and ADMIN")
    void updateUserRole_asAdmin_success() throws Exception {
        authenticateAs("ADMIN");

        UUID userId = UUID.randomUUID();
        Customer existing = new Customer("Bob", "bob@order.com", "hash", "CUSTOMER");
        existing.setId(userId);

        when(customerRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRoleRequest request = new UpdateUserRoleRequest("INVENTORY_MANAGER");

        mockMvc.perform(patch("/api/customers/{id}/role", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.role").value("INVENTORY_MANAGER"));
    }

    @Test
    @DisplayName("PATCH /api/customers/{id}/role prevents demoting primary admin@order.com")
    void updateUserRole_demotePrimaryAdmin_returnsBadRequest() throws Exception {
        authenticateAs("ADMIN");

        UUID adminId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
        Customer primaryAdmin = new Customer("Admin", "admin@order.com", "hash", "ADMIN");
        primaryAdmin.setId(adminId);

        when(customerRepository.findById(adminId)).thenReturn(Optional.of(primaryAdmin));

        UpdateUserRoleRequest request = new UpdateUserRoleRequest("CUSTOMER");

        mockMvc.perform(patch("/api/customers/{id}/role", adminId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Cannot demote the primary system administrator (admin@order.com)"));
    }
}
