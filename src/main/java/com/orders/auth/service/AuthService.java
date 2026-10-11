package com.orders.auth.service;

import com.orders.auth.dto.AuthResponse;
import com.orders.auth.dto.LoginRequest;
import com.orders.auth.dto.RegisterRequest;
import com.orders.auth.dto.UserDto;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import com.orders.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Customer customer = customerRepository.findByEmailIgnoreCase(request.email().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        // Seeded customers from Flyway initially have null passwordHash; initialize with default password123 on first login
        if (customer.getPasswordHash() == null || customer.getPasswordHash().isBlank()) {
            if (!"password123".equals(request.password())) {
                throw new BadCredentialsException("Invalid email or password (default password for seeded accounts is password123)");
            }
            customer.setPasswordHash(passwordEncoder.encode(request.password()));
            customerRepository.save(customer);
        } else {
            if (!passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
                throw new BadCredentialsException("Invalid email or password");
            }
        }

        String role = customer.getRole() != null ? customer.getRole() : "CUSTOMER";
        AuthenticatedUser authUser = new AuthenticatedUser(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                role
        );
        String token = jwtService.generateToken(authUser);

        log.info("User logged in: {} ({}) with role: {}", customer.getEmail(), customer.getId(), role);

        return new AuthResponse(
                token,
                new UserDto(customer.getId(), customer.getName(), customer.getEmail(), role)
        );
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (customerRepository.findByEmailIgnoreCase(request.email().trim()).isPresent()) {
            throw new IllegalStateException("Email is already registered: " + request.email());
        }

        Customer customer = new Customer(
                request.name().trim(),
                request.email().trim().toLowerCase(),
                passwordEncoder.encode(request.password()),
                "CUSTOMER"
        );

        Customer saved = customerRepository.save(customer);
        AuthenticatedUser authUser = new AuthenticatedUser(
                saved.getId(),
                saved.getName(),
                saved.getEmail(),
                saved.getRole()
        );
        String token = jwtService.generateToken(authUser);

        log.info("Registered new customer: {} ({})", saved.getEmail(), saved.getId());

        return new AuthResponse(
                token,
                new UserDto(saved.getId(), saved.getName(), saved.getEmail(), saved.getRole())
        );
    }

    public UserDto getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BadCredentialsException("Not authenticated");
        }

        return new UserDto(user.id(), user.name(), user.email(), user.role());
    }
}
