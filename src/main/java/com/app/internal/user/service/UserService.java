package com.app.internal.user.service;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.RoleNotFoundException;
import com.app.internal.common.exception.UserNotFoundException;
import com.app.internal.role.entity.Role;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.user.dto.UserResponse;
import com.app.internal.user.dto.UserUpdateRequest;
import com.app.internal.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String STATUS_INACTIVE = "INACTIVE";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    // Soft-delete: KHÔNG gọi userRepository.delete()/deleteById() (DELETE
    // cứng) vì Booking.user tham chiếu FK tới đúng row này (xem Booking.java)
    // - xoá cứng sẽ vi phạm ràng buộc FK nếu user đã có booking. Thay vào đó
    // chỉ đánh dấu deletedAt + status=INACTIVE, row vẫn còn nguyên trong DB.
    @Transactional
    public void softDeleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy user với id=" + userId));

        if (user.getDeletedAt() != null) {
            return; // đã xoá từ trước -> idempotent, gọi lại nhiều lần không lỗi
        }

        user.setDeletedAt(LocalDateTime.now());
        user.setStatus(STATUS_INACTIVE);
        // Không cần gọi userRepository.save(user) - "user" đang là managed
        // entity trong persistence context của transaction này (vừa lấy ra từ
        // findById()), Hibernate tự phát hiện thay đổi field (dirty checking)
        // và tự sinh UPDATE lúc flush/commit.
    }

    // @Transactional(readOnly = true) BẮT BUỘC từ Task 3: user.getRole() giờ
    // là quan hệ FetchType.LAZY (trước là String, không cần session). Không
    // có transaction bao ngoài, SimpleJpaRepository.findById() tự mở rồi tự
    // đóng Session ngay khi trả về - toResponse() gọi user.getRole().getName()
    // sau đó sẽ ném LazyInitializationException (xem UserLazyLoadingTest).
    // Việc này "vô tình chạy được" qua HTTP thật vì spring.jpa.open-in-view
    // giữ Session mở cho cả request - không nên dựa vào đó.
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy user với id=" + userId));

        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(String status, String email, Pageable pageable) {
        Page<User> userPage = userRepository.findByStatusAndEmailContainingIgnoreCase(status, email, pageable);

        return userPage.map(this::toResponse);
    }

    @Transactional
    public UserResponse updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        user.setFullName(request.getFullName());
        return toResponse(user);
    }

    @Transactional
    public UserResponse changeStatus(Long userId, String newStatus) {
        // INACTIVE dành riêng cho soft-delete (đi kèm deletedAt) - chặn ở đây
        // để tránh 1 user bị đánh dấu INACTIVE mà deletedAt vẫn null, trông
        // giống "đã xoá" nhưng thật ra không đi qua đúng luồng softDeleteUser.
        if (STATUS_INACTIVE.equals(newStatus)) {
            throw new IllegalArgumentException(
                    "Không thể đổi status thành INACTIVE qua API này - dùng DELETE /admin/users/{id} để xoá user.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        user.setStatus(newStatus);
        return toResponse(user);
    }

    @Transactional
    public UserResponse assignRole(Long userId, Long roleId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy user với id=" + userId));

        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Không tìm thấy role với id=" + roleId));

        user.setRole(role);
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus(),
                user.getRole().getName(),
                user.getCreatedAt());
    }
}
