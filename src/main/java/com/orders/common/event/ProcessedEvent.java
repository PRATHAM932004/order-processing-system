package com.orders.common.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_events", uniqueConstraints = {
    @UniqueConstraint(name = "uq_processed_events", columnNames = {"event_id", "consumer_name"})
})
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "consumer_name", nullable = false, length = 50)
    private String consumerName;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, String consumerName) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.processedAt = Instant.now();
    }
}
