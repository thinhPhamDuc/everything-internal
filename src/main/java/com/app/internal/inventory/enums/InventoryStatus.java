package com.app.internal.inventory.enums;

public enum InventoryStatus {
    OPEN,
    CLOSED,
    // Dành cho "xoá" qua DELETE /admin/inventory/{id} - KHÔNG xoá cứng row,
    // vì Task 7 sau này Booking sẽ tham chiếu FK tới đúng row này.
    CANCELLED
}
