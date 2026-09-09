package com.app.internal.user.enums;

public enum UserStatus {
    ACTIVE,
    LOCKED,
    // Dành riêng cho soft-delete (UserService.softDeleteUser) - KHÔNG set qua
    // PATCH /admin/users/{id}/status, phải đi qua DELETE /admin/users/{id} vì
    // soft-delete còn phải set thêm deletedAt, không chỉ đổi mỗi status.
    INACTIVE
}
