package com.app.internal.role;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.DuplicateRoleNameException;
import com.app.internal.common.exception.RoleInUseException;
import com.app.internal.role.dto.AssignPermissionRequest;
import com.app.internal.role.dto.RoleCreateRequest;
import com.app.internal.role.dto.RoleResponse;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.role.service.RoleService;
import com.app.internal.user.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Kiểm chứng các bẫy đã nêu trong TASK3_CRUD_ROLES.md Bước 7: assignPermissions
// là REPLACE (không phải cộng dồn), và deleteRole phải chặn khi còn user tham
// chiếu thay vì để vỡ FK ở tầng DB. Chạy trên Postgres thật, mỗi test tự tạo 1
// role riêng (tên random) để không đụng seed ADMIN/STAFF/CUSTOMER.
@SpringBootTest
class RoleServiceTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long roleId;
    private Long userId;

    @BeforeEach
    void setUp() {
        RoleCreateRequest request = new RoleCreateRequest();
        request.setName("TEST_ROLE_" + System.nanoTime());
        request.setDescription("Role tạo riêng cho RoleServiceTest");
        roleId = roleService.createRole(request).id();
        userId = null;
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            userRepository.deleteById(userId);
        }
        roleRepository.findById(roleId).ifPresent(roleRepository::delete);
    }

    @Test
    void createRole_trungTenVoiSeedData_nemDuplicateRoleNameException() {
        RoleCreateRequest request = new RoleCreateRequest();
        request.setName("ADMIN");
        request.setDescription("trung ten voi role ADMIN da seed");

        assertThrows(DuplicateRoleNameException.class, () -> roleService.createRole(request));
    }

    @Test
    void assignPermissions_ganLanThu2_replaceToanBoDanhSachCu_khongCongDon() {
        AssignPermissionRequest first = new AssignPermissionRequest();
        first.setPermissionCodes(List.of("USER_READ"));
        roleService.assignPermissions(roleId, first);

        AssignPermissionRequest second = new AssignPermissionRequest();
        second.setPermissionCodes(List.of("ROLE_MANAGE"));
        RoleResponse response = roleService.assignPermissions(roleId, second);

        assertEquals(List.of("ROLE_MANAGE"), response.permissions(),
                "Lần gán thứ 2 phải THAY THẾ, không cộng dồn với USER_READ đã gán trước đó");
    }

    @Test
    void deleteRole_dangCoUserSuDung_nemRoleInUseException() {
        User user = userRepository.save(User.builder()
                .email("role-in-use-" + System.nanoTime() + "@example.com")
                .password(passwordEncoder.encode("irrelevant-password"))
                .fullName("Role In Use Test")
                .status("ACTIVE")
                .role(roleRepository.findById(roleId).orElseThrow())
                .createdAt(LocalDateTime.now())
                .build());
        userId = user.getId();

        assertThrows(RoleInUseException.class, () -> roleService.deleteRole(roleId));

        // Role vẫn còn nguyên vì bị chặn trước khi xoá thật.
        assertTrue(roleRepository.findById(roleId).isPresent());
    }

    @Test
    void deleteRole_khongCoUserNaoSuDung_xoaThanhCong() {
        assertDoesNotThrow(() -> roleService.deleteRole(roleId));
        assertTrue(roleRepository.findById(roleId).isEmpty());
    }
}
