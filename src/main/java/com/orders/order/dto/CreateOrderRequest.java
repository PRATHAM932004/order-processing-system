package com.orders.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {

    @NotNull(message = "customerId is required")
    private UUID customerId;

    @NotEmpty(message = "items list must not be empty")
    private List<@Valid OrderItemRequest> items;

    private String paymentMethod;

    public CreateOrderRequest(UUID customerId, List<OrderItemRequest> items) {
        this.customerId = customerId;
        this.items = items;
        this.paymentMethod = "UPI";
    }
}
