package com.orders.order.controller;

import com.orders.order.dto.KafkaEventResponse;
import com.orders.order.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<List<KafkaEventResponse>> getEvents(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String topic
    ) {
        return ResponseEntity.ok(eventService.getEvents(orderId, topic));
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleForbidden(AccessDeniedException ex) {
        return Map.of("error", ex.getMessage());
    }
}

