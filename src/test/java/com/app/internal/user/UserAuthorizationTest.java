package com.app.internal.user;

import com.app.internal.user.controller.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Kiểm chứng @PreAuthorize("hasAuthority('USER_MANAGE')") trên UserController
// thực sự chặn user không có permission này, KHÔNG chỉ nhìn code mà tưởng đã
// áp dụng. Gọi thẳng bean UserController (không dùng MockMvc) - vì
// @EnableMethodSecurity bọc security interceptor quanh chính bean này, gọi
// trực tiếp method vẫn kích hoạt check.
//
// Task 3: UserController đổi từ hasRole('ADMIN') sang hasAuthority('USER_MANAGE')
// - "quyền" (STAFF được cấp USER_MANAGE) tách biệt khỏi "vai trò ADMIN".
@SpringBootTest
class UserAuthorizationTest {

    @Autowired
    private UserController userController;

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerKhongCoPermission_bi403_AccessDeniedException() {
        assertThrows(AccessDeniedException.class,
                () -> userController.list("", "", PageRequest.of(0, 20)));
    }

    @Test
    @WithMockUser(authorities = "USER_MANAGE")
    void coPermissionUserManage_thanhCong() {
        assertDoesNotThrow(() -> userController.list("", "", PageRequest.of(0, 20)));
    }

    // Chứng minh đúng ý Task 3: KHÔNG cần role ADMIN, chỉ cần permission
    // USER_MANAGE (VD role STAFF được gán permission này) là gọi được.
    // Lưu ý: @WithMockUser không cho khai cùng lúc roles() và authorities()
    // (ném lỗi lúc chạy) - phải tự thêm tiền tố "ROLE_" thủ công trong authorities().
    @Test
    @WithMockUser(authorities = {"ROLE_STAFF", "USER_MANAGE"})
    void staffCoPermissionUserManage_khongCanLaAdmin_thanhCong() {
        assertDoesNotThrow(() -> userController.list("", "", PageRequest.of(0, 20)));
    }
}
