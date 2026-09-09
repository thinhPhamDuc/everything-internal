package com.app.internal.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// KHÔNG có field "status"/"roles" ở đây CỐ Ý - đổi status/role là hành động
// nhạy cảm, phải đi qua endpoint riêng (PATCH /{id}/status) để dễ audit/log
// riêng, tránh 1 endpoint "update chung chung" âm thầm cho đổi trạng thái.
@Data
public class UserUpdateRequest {

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(min = 2, max = 50, message = "Họ và tên phải từ 2 đến 50 ký tự")
    private String fullName;
}
