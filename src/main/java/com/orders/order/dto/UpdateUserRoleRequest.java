package com.orders.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateUserRoleRequest(
    @NotBlank(message = "Role is required")
    @Pattern(
        regexp = "^(CUSTOMER|INVENTORY_MANAGER|ADMIN)$",
        message = "Role must be CUSTOMER, INVENTORY_MANAGER, or ADMIN"
    )
    String role
) {}
