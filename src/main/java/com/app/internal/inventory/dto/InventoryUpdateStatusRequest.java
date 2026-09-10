package com.app.internal.inventory.dto;

import com.app.internal.inventory.enums.InventoryStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InventoryUpdateStatusRequest {

    @NotNull(message = "Trạng thái không được để trống")
    private InventoryStatus status;
}
