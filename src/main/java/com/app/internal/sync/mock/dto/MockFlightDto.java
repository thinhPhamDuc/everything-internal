package com.app.internal.sync.mock.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Giả lập response của hệ thống đối tác bên thứ 3 - cố ý đặt tên field
// khác 1 chút so với FlightTicketInventory thật (VD seatsLeft thay vì
// availableSeats, seatClass là String thay vì enum nội bộ) để Bước 8
// (Processor) có việc "map" dữ liệu thô sang entity thật, không phải copy
// nguyên field.
public record MockFlightDto(
        String flightCode,
        String airline,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        String seatClass,
        BigDecimal price,
        Integer seatsLeft
) {
}
