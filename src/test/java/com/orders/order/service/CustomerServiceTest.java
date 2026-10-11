package com.orders.order.service;

import com.orders.order.dto.CreateUserRequest;
import com.orders.order.dto.CustomerResponse;
import com.orders.order.dto.UpdateUserRoleRequest;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CustomerService customerService;

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
    @DisplayName("getAllCustomers returns sorted CustomerResponse list when authenticated as ADMIN")
    void getAllCustomers_asAdmin_success() {
        authenticateAs("ADMIN");

        Customer c1 = new Customer("Admin", "admin@order.com", "hash1", "ADMIN");
        c1.setId(UUID.randomUUID());
        c1.setCreatedAt(Instant.now());

        Customer c2 = new Customer("Alice", "alice@order.com", "hash2", "CUSTOMER");
        c2.setId(UUID.randomUUID());
        c2.setCreatedAt(Instant.now().minusSeconds(60));

        when(customerRepository.findAll()).thenReturn(List.of(c2, c1));

        List<CustomerResponse> result = customerService.getAllCustomers();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).email()).isEqualTo("admin@order.com");
        assertThat(result.get(1).email()).isEqualTo("alice@order.com");
    }

    @Test
    @DisplayName("getAllCustomers throws AccessDeniedException when not ADMIN")
    void getAllCustomers_nonAdmin_throwsAccessDenied() {
        authenticateAs("CUSTOMER");

        assertThatThrownBy(() -> customerService.getAllCustomers())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only ADMIN users can list all customers");
    }

    @Test
    @DisplayName("createUser encodes password and saves staff user to CustomerRepository")
    void createUser_asAdmin_success() {
        authenticateAs("ADMIN");

        CreateUserRequest request = new CreateUserRequest(
                "Warehouse Lead",
                "warehouse.lead@order.com",
                "password123",
                "INVENTORY_MANAGER"
        );

        UUID id = UUID.randomUUID();
        when(customerRepository.findByEmailIgnoreCase("warehouse.lead@order.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("bcrypt_hash");
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(id);
            return c;
        });

        CustomerResponse created = customerService.createUser(request);

        assertThat(created.id()).isEqualTo(id);
        assertThat(created.email()).isEqualTo("warehouse.lead@order.com");
        assertThat(created.role()).isEqualTo("INVENTORY_MANAGER");
    }

    @Test
    @DisplayName("createUser throws IllegalStateException when email already exists")
    void createUser_duplicateEmail_throwsIllegalState() {
        authenticateAs("ADMIN");

        CreateUserRequest request = new CreateUserRequest(
                "Duplicate",
                "existing@order.com",
                "password123",
                "CUSTOMER"
        );

        when(customerRepository.findByEmailIgnoreCase("existing@order.com"))
                .thenReturn(Optional.of(new Customer("Existing", "existing@order.com")));

        assertThatThrownBy(() -> customerService.createUser(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email is already registered: existing@order.com");
    }

    @Test
    @DisplayName("updateUserRole updates role and saves to CustomerRepository")
    void updateUserRole_asAdmin_success() {
        authenticateAs("ADMIN");

        UUID userId = UUID.randomUUID();
        Customer existing = new Customer("Bob", "bob@order.com", "hash", "CUSTOMER");
        existing.setId(userId);

        when(customerRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerResponse updated = customerService.updateUserRole(userId, new UpdateUserRoleRequest("INVENTORY_MANAGER"));

        assertThat(updated.role()).isEqualTo("INVENTORY_MANAGER");
    }

    @Test
    @DisplayName("updateUserRole prevents demoting primary admin@order.com")
    void updateUserRole_demotePrimaryAdmin_throwsIllegalArgument() {
        authenticateAs("ADMIN");

        UUID adminId = UUID.fromString("a1111111-1111-1111-1111-111111111111");
        Customer primaryAdmin = new Customer("Admin", "admin@order.com", "hash", "ADMIN");
        primaryAdmin.setId(adminId);

        when(customerRepository.findById(adminId)).thenReturn(Optional.of(primaryAdmin));

        assertThatThrownBy(() -> customerService.updateUserRole(adminId, new UpdateUserRoleRequest("CUSTOMER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot demote the primary system administrator (admin@order.com)");
    }
}
