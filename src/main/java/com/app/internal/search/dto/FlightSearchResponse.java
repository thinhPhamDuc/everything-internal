package com.app.internal.search.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// CỐ Ý không có sourceSystem/totalSeats/status/version - field nội bộ,
// không lộ ra API public (đúng nguyên tắc TASK6_SEARCH_REDIS_CACHE.md).
// "provider" NGƯỢC LẠI cố ý CÓ mặt (khác sourceSystem) - Task 9 giữ riêng
// từng dòng Inventory theo provider, khách cần thấy "vé nào của nguồn nào"
// để so sánh, không phải field nội bộ nhạy cảm.
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
        Integer availableSeats,
        String provider) {
}
