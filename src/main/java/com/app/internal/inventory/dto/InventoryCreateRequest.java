package com.app.internal.inventory.dto;

import com.app.internal.inventory.enums.SeatClass;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// KHÔNG có field "availableSeats" CỐ Ý - vé vừa tạo thì chưa ai đặt, nên
// Service luôn tự set availableSeats = totalSeats, không cho admin nhập 2 số
// độc lập (dễ tạo trạng thái vô lý ngay từ đầu).
@Data
public class InventoryCreateRequest {

    @NotBlank(message = "Mã chuyến bay không được để trống")
    private String flightCode;

    @NotBlank(message = "Hãng bay không được để trống")
    private String airline;

    @NotBlank(message = "Điểm đi không được để trống")
    private String origin;

    @NotBlank(message = "Điểm đến không được để trống")
    private String destination;

    @NotNull(message = "Giờ khởi hành không được để trống")
    @Future(message = "Giờ khởi hành phải ở tương lai")
    private LocalDateTime departureTime;

    @NotNull(message = "Giờ hạ cánh không được để trống")
    private LocalDateTime arrivalTime;

    @NotNull(message = "Hạng ghế không được để trống")
    private SeatClass seatClass;

    @NotNull(message = "Giá vé không được để trống")
    @Positive(message = "Giá vé phải lớn hơn 0")
    private BigDecimal price;

    @NotNull(message = "Tổng số ghế không được để trống")
    @Positive(message = "Tổng số ghế phải lớn hơn 0")
    private Integer totalSeats;
}
