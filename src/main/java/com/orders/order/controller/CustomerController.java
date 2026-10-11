package com.orders.order.controller;

import com.orders.order.dto.CreateUserRequest;
import com.orders.order.dto.CustomerResponse;
import com.orders.order.dto.UpdateUserRoleRequest;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Slf4j
public class CustomerController {

    private static final String PRIMARY_ADMIN_EMAIL = "admin@order.com";

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public ResponseEntity<?> getAllCustomers() {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN users can list all customers"));
        }

        List<CustomerResponse> customers = customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toCustomerResponse)
                .toList();

        return ResponseEntity.ok(customers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCustomerById(@PathVariable UUID id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)
                || (!user.isAdmin() && !user.id().equals(id))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied"));
        }

        return customerRepository.findById(id)
                .map(c -> ResponseEntity.ok(toCustomerResponse(c)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @Transactional
    public ResponseEntity<?> createUser(@Valid @RequestBody CreateUserRequest request) {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN users can create user accounts"));
        }

        String normalizedEmail = request.email().trim().toLowerCase();
        if (customerRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email is already registered: " + normalizedEmail));
        }

        String normalizedRole = request.role().trim().toUpperCase();
        Customer customer = new Customer(
                request.name().trim(),
                normalizedEmail,
                passwordEncoder.encode(request.password()),
                normalizedRole
        );

        Customer saved = customerRepository.save(customer);
        log.info("Admin created new user account: {} ({}) with role: {}",
                saved.getEmail(), saved.getId(), saved.getRole());

        return ResponseEntity.status(HttpStatus.CREATED).body(toCustomerResponse(saved));
    }

    @PatchMapping("/{id}/role")
    @Transactional
    public ResponseEntity<?> updateUserRole(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        if (!isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN users can change user roles"));
        }

        Optional<Customer> customerOpt = customerRepository.findById(id);
        if (customerOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found: " + id));
        }

        Customer customer = customerOpt.get();
        String newRole = request.role().trim().toUpperCase();

        if (PRIMARY_ADMIN_EMAIL.equalsIgnoreCase(customer.getEmail()) && !"ADMIN".equalsIgnoreCase(newRole)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Cannot demote the primary system administrator (admin@order.com)"));
        }

        String previousRole = customer.getRole();
        customer.setRole(newRole);
        Customer saved = customerRepository.save(customer);

        log.info("Admin updated role for user {} ({}): {} -> {}",
                saved.getEmail(), saved.getId(), previousRole, saved.getRole());

        return ResponseEntity.ok(toCustomerResponse(saved));
    }

    private CustomerResponse toCustomerResponse(Customer c) {
        return new CustomerResponse(
                c.getId(),
                c.getName(),
                c.getEmail(),
                c.getRole() != null ? c.getRole() : "CUSTOMER",
                c.getCreatedAt()
        );
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.isAdmin();
        }
        return false;
    }
}

