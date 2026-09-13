package com.app.internal.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InventoryResponse(
        Long id,
        String flightCode,
        String airline,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        String seatClass,
        BigDecimal price,
        Integer totalSeats,
        Integer availableSeats,
        String status,
        String sourceSystem,
        String provider,
        LocalDateTime lastSyncedAt) {
}
