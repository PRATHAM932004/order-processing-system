package com.orders.order.service;

import com.orders.order.dto.CreateUserRequest;
import com.orders.order.dto.CustomerResponse;
import com.orders.order.dto.UpdateUserRoleRequest;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private static final String PRIMARY_ADMIN_EMAIL = "admin@order.com";

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public List<CustomerResponse> getAllCustomers() {
        if (!isAdmin()) {
            throw new AccessDeniedException("Only ADMIN users can list all customers");
        }

        return customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toCustomerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(UUID id) {
        AuthenticatedUser currentUser = getCurrentAuthenticatedUser();
        if (currentUser == null || (!currentUser.isAdmin() && !currentUser.id().equals(id))) {
            throw new AccessDeniedException("Access denied");
        }

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        return toCustomerResponse(customer);
    }

    @Transactional
    public CustomerResponse createUser(CreateUserRequest request) {
        if (!isAdmin()) {
            throw new AccessDeniedException("Only ADMIN users can create user accounts");
        }

        String normalizedEmail = request.email().trim().toLowerCase();
        if (customerRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new IllegalStateException("Email is already registered: " + normalizedEmail);
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

        return toCustomerResponse(saved);
    }

    @Transactional
    public CustomerResponse updateUserRole(UUID id, UpdateUserRoleRequest request) {
        if (!isAdmin()) {
            throw new AccessDeniedException("Only ADMIN users can change user roles");
        }

        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        String newRole = request.role().trim().toUpperCase();
        if (PRIMARY_ADMIN_EMAIL.equalsIgnoreCase(customer.getEmail()) && !"ADMIN".equalsIgnoreCase(newRole)) {
            throw new IllegalArgumentException("Cannot demote the primary system administrator (admin@order.com)");
        }

        String previousRole = customer.getRole();
        customer.setRole(newRole);
        Customer saved = customerRepository.save(customer);

        log.info("Admin updated role for user {} ({}): {} -> {}",
                saved.getEmail(), saved.getId(), previousRole, saved.getRole());

        return toCustomerResponse(saved);
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
        AuthenticatedUser user = getCurrentAuthenticatedUser();
        return user != null && user.isAdmin();
    }

    private AuthenticatedUser getCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}
