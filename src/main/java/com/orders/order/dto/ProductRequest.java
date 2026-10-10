package com.orders.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record ProductRequest(
    @NotBlank(message = "Product name is required")
    String name,

    @NotBlank(message = "Product category is required")
    String category,

    @NotNull(message = "Price is required")
    @PositiveOrZero(message = "Price must be zero or positive")
    BigDecimal price,

    @NotNull(message = "Stock quantity is required")
    @PositiveOrZero(message = "Stock quantity must be zero or positive")
    Integer stockQuantity
) {}
