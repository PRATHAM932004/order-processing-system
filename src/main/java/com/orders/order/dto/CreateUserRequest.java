package com.orders.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank(message = "Name is required")
    String name,

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email is required")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    String password,

    @NotBlank(message = "Role is required")
    @Pattern(
        regexp = "^(CUSTOMER|INVENTORY_MANAGER|ADMIN)$",
        message = "Role must be CUSTOMER, INVENTORY_MANAGER, or ADMIN"
    )
    String role
) {}
