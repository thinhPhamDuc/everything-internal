package com.app.internal.role.controller;

import com.app.internal.role.dto.AssignPermissionRequest;
import com.app.internal.role.dto.RoleCreateRequest;
import com.app.internal.role.dto.RoleResponse;
import com.app.internal.role.dto.RoleUpdateRequest;
import com.app.internal.role.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// CỐ Ý giữ hasRole('ADMIN') tuyệt đối thay vì permission - CRUD role/permission
// là hành động cực nhạy cảm (có thể tự cấp quyền cho chính mình nếu lỏng),
// khác với UserController đã đổi sang hasAuthority ở Bước 11 của giáo án.
@RestController
@RequestMapping("/admin/roles")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    public ResponseEntity<List<RoleResponse>> list() {
        return ResponseEntity.ok(roleService.listRoles());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleResponse> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(roleService.getRoleById(id));
    }

    @PostMapping
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleService.createRole(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoleResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody RoleUpdateRequest request) {
        return ResponseEntity.ok(roleService.updateRole(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/permissions")
    public ResponseEntity<RoleResponse> assignPermissions(
            @PathVariable("id") Long id,
            @Valid @RequestBody AssignPermissionRequest request) {
        return ResponseEntity.ok(roleService.assignPermissions(id, request));
    }
}
