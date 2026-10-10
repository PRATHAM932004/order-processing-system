package com.orders.auth;

import com.orders.auth.dto.AuthResponse;
import com.orders.auth.dto.LoginRequest;
import com.orders.auth.dto.RegisterRequest;
import com.orders.auth.dto.UserDto;
import com.orders.order.entity.Customer;
import com.orders.order.repository.CustomerRepository;
import com.orders.security.AuthenticatedUser;
import com.orders.security.JwtService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    @Transactional
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        Optional<Customer> customerOpt = customerRepository.findByEmailIgnoreCase(request.email().trim());
        if (customerOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }

        Customer customer = customerOpt.get();

        // Seeded customers from Flyway initially have null passwordHash; initialize with default password123 on first login
        if (customer.getPasswordHash() == null || customer.getPasswordHash().isBlank()) {
            if (!"password123".equals(request.password())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid email or password (default password for seeded accounts is password123)"));
            }
            customer.setPasswordHash(passwordEncoder.encode(request.password()));
            customerRepository.save(customer);
        } else {
            if (!passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid email or password"));
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

        return ResponseEntity.ok(new AuthResponse(
                token,
                new UserDto(customer.getId(), customer.getName(), customer.getEmail(), role)
        ));
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        if (customerRepository.findByEmailIgnoreCase(request.email().trim()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Email is already registered: " + request.email()));
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

        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponse(
                token,
                new UserDto(saved.getId(), saved.getName(), saved.getEmail(), saved.getRole())
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        return ResponseEntity.ok(new UserDto(user.id(), user.name(), user.email(), user.role()));
    }
}
