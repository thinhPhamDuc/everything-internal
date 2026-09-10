package com.app.internal.user.controller;

import com.app.internal.auth.service.AuthService;
import com.app.internal.user.dto.AssignRoleRequest;
import com.app.internal.user.dto.UserResponse;
import com.app.internal.user.dto.UserUpdateRequest;
import com.app.internal.user.dto.UserUpdateStatusRequest;
import com.app.internal.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// Task 3: đổi từ hasRole('ADMIN') sang hasAuthority theo permission - cho
// phép sau này gán quyền USER_MANAGE cho STAFF mà không cần lên hẳn ADMIN.
// RoleController (CRUD role/permission) vẫn giữ hasRole('ADMIN') tuyệt đối vì
// đó là hành động nhạy cảm hơn (có thể tự cấp quyền cho chính mình nếu lỏng).
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasAuthority('USER_MANAGE')")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<Page<UserResponse>> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String email,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(userService.listUsers(status, email, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateStatusRequest request) { // Hoặc dùng @RequestParam nếu truyền qua query
        userService.changeStatus(id, request.getStatus().name());
        return ResponseEntity.noContent().build(); // Trả về 204 No Content vì chỉ đổi trạng thái
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") Long id) {
        userService.softDeleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<UserResponse> assignRole(
            @PathVariable("id") Long id,
            @Valid @RequestBody AssignRoleRequest request) {
        return ResponseEntity.ok(userService.assignRole(id, request.getRoleId()));
    }
}
