package com.app.internal.role.dto;

import lombok.Data;

// CỐ Ý không có field "name" - name là định danh nghiệp vụ, được nhiều nơi
// tham chiếu (User.role, seed data, luồng register mặc định CUSTOMER...). Đổi
// tên 1 role đang được dùng là hành động rủi ro cao, nên tạo role mới thay vì
// sửa tên role cũ (giống lý do UserUpdateRequest không cho sửa status).
@Data
public class RoleUpdateRequest {

    private String description;
}
