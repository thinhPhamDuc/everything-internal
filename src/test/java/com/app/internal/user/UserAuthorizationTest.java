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

// Kiểm chứng @PreAuthorize("hasRole('ADMIN')") trên UserController thực sự
// chặn CUSTOMER, KHÔNG chỉ nhìn code mà tưởng đã áp dụng. Gọi thẳng bean
// UserController (không dùng MockMvc) - vì @EnableMethodSecurity bọc security
// interceptor quanh chính bean này, gọi trực tiếp method vẫn kích hoạt check.
@SpringBootTest
class UserAuthorizationTest {

    @Autowired
    private UserController userController;

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerGoiApiAdmin_bi403_AccessDeniedException() {
        assertThrows(AccessDeniedException.class,
                () -> userController.list("", "", PageRequest.of(0, 20)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminGoiApiAdmin_thanhCong() {
        assertDoesNotThrow(() -> userController.list("", "", PageRequest.of(0, 20)));
    }
}
