package com.app.internal.search.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// CỐ Ý không có sourceSystem/totalSeats/status/version - field nội bộ,
// không lộ ra API public (đúng nguyên tắc TASK6_SEARCH_REDIS_CACHE.md).
public record FlightSearchResponse(
        Long id,
        String flightCode,
        String airline,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        String seatClass,
        BigDecimal price,
        Integer availableSeats) {
}
