package com.app.internal.role.service;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.DuplicateRoleNameException;
import com.app.internal.common.exception.RoleInUseException;
import com.app.internal.common.exception.RoleNotFoundException;
import com.app.internal.role.dto.AssignPermissionRequest;
import com.app.internal.role.dto.RoleCreateRequest;
import com.app.internal.role.dto.RoleResponse;
import com.app.internal.role.dto.RoleUpdateRequest;
import com.app.internal.role.entity.Permission;
import com.app.internal.role.entity.Role;
import com.app.internal.role.repository.PermissionRepository;
import com.app.internal.role.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    // @Transactional(readOnly = true) bắt buộc - role.getPermissions() là
    // quan hệ FetchType.LAZY, cần Session còn mở lúc toResponse() đọc nó
    // (cùng lý do đã áp dụng cho UserService.getUserById/listUsers).
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse getRoleById(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RoleNotFoundException("Không tìm thấy role với id=" + id));
        return toResponse(role);
    }

    @Transactional
    public RoleResponse createRole(RoleCreateRequest request) {
        if (roleRepository.existsByName(request.getName())) {
            throw new DuplicateRoleNameException("Tên role đã tồn tại: " + request.getName());
        }

        Role role = Role.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();

        return toResponse(roleRepository.save(role));
    }

    @Transactional
    public RoleResponse updateRole(Long id, RoleUpdateRequest request) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RoleNotFoundException("Không tìm thấy role với id=" + id));

        role.setDescription(request.getDescription());
        return toResponse(role);
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new RoleNotFoundException("Không tìm thấy role với id=" + id));

        if (userRepository.existsByRoleId(id)) {
            throw new RoleInUseException(
                    "Không thể xoá role đang có user sử dụng - đổi role cho các user đó trước.");
        }

        roleRepository.delete(role);
    }

    @Transactional
    public RoleResponse assignPermissions(Long roleId, AssignPermissionRequest request) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Không tìm thấy role với id=" + roleId));

        List<Permission> permissions = permissionRepository.findByCodeIn(request.getPermissionCodes());

        // Replace toàn bộ danh sách permission hiện có bằng danh sách mới -
        // không phải "thêm vào". Muốn thêm 1 permission, client tự gửi lại
        // đủ danh sách cũ + permission mới.
        Set<Permission> replaced = new HashSet<>(permissions);
        role.setPermissions(replaced);

        return toResponse(role);
    }

    private RoleResponse toResponse(Role role) {
        List<String> permissionCodes = role.getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();

        return new RoleResponse(role.getId(), role.getName(), role.getDescription(), permissionCodes);
    }
}
