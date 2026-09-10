package com.app.internal.user;

import com.app.internal.auth.dto.LoginRequest;
import com.app.internal.auth.repository.UserRepository;
import com.app.internal.auth.service.AuthService;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.user.dto.UserResponse;
import com.app.internal.user.dto.UserUpdateRequest;
import com.app.internal.user.entity.User;
import com.app.internal.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Kiểm chứng đúng ghi chú Bước 7 (TASK2_CRUD_USERS.md): khoá tài khoản qua
// changeStatus() phải có tác dụng thật ở login(), không chỉ "trên giấy". Cộng
// thêm vài critical path còn lại của Task 2 (list có filter, update giữ
// nguyên status). Chạy trên Postgres thật, cùng phong cách UserSoftDeleteTest.
@SpringBootTest
class UserStatusTest {

    private static final String RAW_PASSWORD = "irrelevant-password";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    private Long userId;
    private String email;

    @BeforeEach
    void setUp() {
        email = "status-test-" + System.nanoTime() + "@example.com";

        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .fullName("Status Test User")
                .status("ACTIVE")
                .role(roleRepository.findByName("CUSTOMER").orElseThrow())
                .createdAt(LocalDateTime.now())
                .build());
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(userId);
    }

    @Test
    void userBiKhoa_thiLoginThatBai() {
        userService.changeStatus(userId, "LOCKED");

        LoginRequest loginRequest = new LoginRequest(email, RAW_PASSWORD);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authService.login(loginRequest));
        assertEquals("Sai email hoặc mật khẩu", ex.getMessage());
    }

    @Test
    void moKhoaLai_thiLoginThanhCongBinhThuong() {
        userService.changeStatus(userId, "LOCKED");
        userService.changeStatus(userId, "ACTIVE");

        LoginRequest loginRequest = new LoginRequest(email, RAW_PASSWORD);

        assertDoesNotThrow(() -> authService.login(loginRequest));
    }

    @Test
    void changeStatus_khongChoDoiThanhINACTIVE_phaiDiQuaDelete() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> userService.changeStatus(userId, "INACTIVE"));
        assertTrue(ex.getMessage().contains("DELETE"));

        // status trên DB không bị đổi vì exception ném ra TRƯỚC khi set field
        assertEquals("ACTIVE", userRepository.findById(userId).orElseThrow().getStatus());
    }

    @Test
    void updateUser_chiSuaFullName_khongDungDeDoiStatus() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setFullName("Ten Da Sua");

        UserResponse response = userService.updateUser(userId, request);

        assertEquals("Ten Da Sua", response.fullName());
        assertEquals("ACTIVE", response.status(), "updateUser không được phép đổi status");
    }

    @Test
    void listUsers_locTheoStatusVaEmail_chiTraDungUser() {
        Page<UserResponse> page = userService.listUsers("ACTIVE", email, PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(email, page.getContent().get(0).email());
    }
}
