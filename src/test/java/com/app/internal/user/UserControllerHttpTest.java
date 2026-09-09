package com.app.internal.user;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Test qua tầng HTTP thật (DispatcherServlet + SecurityFilterChain +
// GlobalExceptionHandler), khác với các test khác trong package này (gọi
// thẳng bean) - cần để xác nhận đúng mã trạng thái HTTP thật sự trả về cho
// client, không chỉ đúng exception ở tầng Java.
//
// KHÔNG dùng @AutoConfigureMockMvc: module spring-boot-webmvc-test (Boot 4)
// không còn tự động áp springSecurity() vào MockMvc như các bản Boot cũ -
// thiếu nó, @WithMockUser không có tác dụng trong request HTTP (SecurityContext
// test không được nạp vào filter chain), mọi request đều bị chặn như anonymous
// dù đã gắn @WithMockUser. Phải tự build MockMvc kèm .apply(springSecurity()).
@SpringBootTest
class UserControllerHttpTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private Long userId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        User user = userRepository.save(User.builder()
                .email("http-test-" + System.nanoTime() + "@example.com")
                .password(passwordEncoder.encode("irrelevant-password"))
                .fullName("Http Test User")
                .status("ACTIVE")
                .roles("CUSTOMER")
                .createdAt(LocalDateTime.now())
                .build());
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteById(userId);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteUser_quaHttp_tra204_vaSoftDeleteThatTrongDB() throws Exception {
        assertNull(userRepository.findById(userId).orElseThrow().getDeletedAt());

        mockMvc.perform(delete("/admin/users/{id}", userId))
                .andExpect(status().isNoContent());

        User afterDelete = userRepository.findById(userId).orElseThrow();
        assertNotNull(afterDelete.getDeletedAt(), "deletedAt phải được set sau khi gọi DELETE qua HTTP");
        assertEquals("INACTIVE", afterDelete.getStatus());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void deleteUser_customerGoiQuaHttp_tra403() throws Exception {
        mockMvc.perform(delete("/admin/users/{id}", userId))
                .andExpect(status().isForbidden());

        // Chưa bị xoá vì request bị chặn trước khi tới UserService
        assertNull(userRepository.findById(userId).orElseThrow().getDeletedAt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getUserById_idKhongTonTai_tra404QuaHttp() throws Exception {
        long khongTonTaiId = -1L;

        mockMvc.perform(get("/admin/users/{id}", khongTonTaiId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // Giáo án (TASK2_CRUD_USERS.md Bước 8) kỳ vọng 401 cho request không kèm
    // token, nhưng thực tế trả 403 - vì SecurityConfig chưa khai báo
    // AuthenticationEntryPoint riêng, Spring Security fallback dùng
    // Http403ForbiddenEntryPoint (không phân biệt "chưa đăng nhập" và "không
    // đủ quyền", cả 2 đều ra 403). Test này ghi nhận đúng hành vi HIỆN TẠI,
    // không phải hành vi giáo án mô tả - báo lại để tự quyết định có cần thêm
    // AuthenticationEntryPoint riêng để tách rõ 401/403 hay không.
    @Test
    void getUserById_khongCoToken_hienTaiTra403KhongPhai401() throws Exception {
        mockMvc.perform(get("/admin/users/{id}", userId))
                .andExpect(status().isForbidden());
    }
}
