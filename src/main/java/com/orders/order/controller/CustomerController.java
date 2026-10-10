package com.orders.order.controller;

import com.orders.order.dto.CustomerResponse;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerRepository customerRepository;

    @GetMapping
    public ResponseEntity<?> getAllCustomers() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user && !user.isAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Only ADMIN users can list all customers"));
        }

        List<CustomerResponse> customers = customerRepository.findAll().stream()
                .map(c -> new CustomerResponse(
                        c.getId(),
                        c.getName(),
                        c.getEmail(),
                        c.getRole() != null ? c.getRole() : "CUSTOMER",
                        c.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok(customers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCustomerById(@PathVariable UUID id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user
                && !user.isAdmin() && !user.id().equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied"));
        }

        return customerRepository.findById(id)
                .map(c -> ResponseEntity.ok(new CustomerResponse(
                        c.getId(),
                        c.getName(),
                        c.getEmail(),
                        c.getRole() != null ? c.getRole() : "CUSTOMER",
                        c.getCreatedAt()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
