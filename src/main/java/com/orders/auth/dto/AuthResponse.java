package com.orders.auth.dto;

public record AuthResponse(
    String token,
    UserDto user
) {}
