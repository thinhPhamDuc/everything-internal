package com.app.internal.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// KHÔNG có field user - endpoint luôn scope theo chính user đang gọi
// (Authentication.getPrincipal()), không cần lặp lại chính danh tính người
// gọi trong response.
public record BookingResponse(
        Long id,
        Long inventoryId,
        String flightCode,
        String airline,
        String origin,
        String destination,
        LocalDateTime departureTime,
        String seatClass,
        Integer passengerCount,
        BigDecimal totalPrice,
        String status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {
}
