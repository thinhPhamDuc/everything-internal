package com.app.internal.search.dto;

import com.app.internal.inventory.enums.SeatClass;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

// departureDate là LocalDate (đúng ngày, không phải giờ chính xác) - đúng
// ghi chú GIAO_AN Task 6; Service tự convert sang khoảng
// [startOfDay, endOfDay) để so khớp với departureTime (LocalDateTime) ở
// Inventory.
public record FlightSearchRequest(
        @NotBlank(message = "Điểm đi không được để trống") String origin,
        @NotBlank(message = "Điểm đến không được để trống") String destination,
        @NotNull(message = "Ngày khởi hành không được để trống")
        @FutureOrPresent(message = "Ngày khởi hành không được ở quá khứ") LocalDate departureDate,
        @NotNull(message = "Hạng ghế không được để trống") SeatClass seatClass,
        @NotNull(message = "Số hành khách không được để trống")
        @Positive(message = "Số hành khách phải lớn hơn 0") Integer passengerCount) {
}
