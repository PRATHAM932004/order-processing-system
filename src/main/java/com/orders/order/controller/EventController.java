package com.orders.order.controller;

import com.orders.order.dto.KafkaEventResponse;
import com.orders.order.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<List<KafkaEventResponse>> getEvents(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) String topic
    ) {
        return ResponseEntity.ok(eventService.getEvents(orderId, topic));
    }
}

