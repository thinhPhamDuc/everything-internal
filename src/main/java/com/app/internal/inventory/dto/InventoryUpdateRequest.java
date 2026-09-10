package com.app.internal.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

// KHÔNG có flightCode/origin/destination/departureTime/seatClass CỐ Ý - tổ
// hợp 5 field đó là định danh nghiệp vụ (unique constraint) của 1 vé, đổi
// field định danh của 1 bản ghi đang tồn tại dễ gây nhầm lẫn dữ liệu -> muốn
// đổi hẳn sang chuyến bay khác thì tạo bản ghi mới, không sửa bản ghi cũ.
@Data
public class InventoryUpdateRequest {

    @NotBlank(message = "Hãng bay không được để trống")
    private String airline;

    @NotNull(message = "Giá vé không được để trống")
    @Positive(message = "Giá vé phải lớn hơn 0")
    private BigDecimal price;

    @NotNull(message = "Tổng số ghế không được để trống")
    @Positive(message = "Tổng số ghế phải lớn hơn 0")
    private Integer totalSeats;
}
