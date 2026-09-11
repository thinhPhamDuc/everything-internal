package com.app.internal.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingCreateRequest(
        @NotNull(message = "inventoryId không được để trống") Long inventoryId,
        @NotNull(message = "Số hành khách không được để trống")
        @Positive(message = "Số hành khách phải lớn hơn 0") Integer passengerCount) {
}
