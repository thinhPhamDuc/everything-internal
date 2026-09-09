package com.app.internal.user.service;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.UserNotFoundException;
import com.app.internal.user.dto.UserResponse;
import com.app.internal.user.dto.UserUpdateRequest;
import com.app.internal.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final String STATUS_INACTIVE = "INACTIVE";

    private final UserRepository userRepository;

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

    public UserResponse getUserById(Long userId) {
        // TODO: tìm user theo id, ném UserNotFoundException nếu không có
        // (exception này đã có sẵn từ soft-delete, dùng lại)
        // TODO: map User -> UserResponse (tự viết method map tay, hoặc constructor
        // của record nhận thẳng entity - tự chọn cách nào rõ ràng hơn với bạn)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy user với id=" + userId));

        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),user.getStatus(),user.getRoles(),user.getCreatedAt());
    }

    public Page<UserResponse> listUsers(String status, String email, Pageable pageable) {
        // TODO: gọi UserRepository.findByStatusAndEmailContainingIgnoreCase(...)
        // TODO: map Page<User> -> Page<UserResponse> (gợi ý: Page có sẵn method
        // .map(Function) rất tiện cho đúng việc này, tìm hiểu javadoc của nó)
        // 1. Gọi UserRepository để lấy ra Page<User>
        Page<User> userPage = userRepository.findByStatusAndEmailContainingIgnoreCase(status, email, pageable);

        // 2. Map Page<User> -> Page<UserResponse> sử dụng phương thức .map()
        Page<UserResponse> userResponsePage = userPage.map(user -> {
            UserResponse response = new UserResponse(user.getId(), user.getEmail(), user.getFullName(),user.getStatus(),user.getRoles(),user.getCreatedAt());
            return response;
        });

        return userResponsePage;
    }

    @Transactional
    public UserResponse updateUser(Long userId, UserUpdateRequest request) {
        // TODO: tìm user, set field mới, KHÔNG cần gọi save() (nhắc lại đúng lý do
        // đã ghi trong QA_KIEN_THUC.md: entity lấy từ findById() trong transaction
        // là managed, Hibernate tự dirty-checking)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + userId));

        // 2. Set các field mới từ request vào entity
        user.setFullName(request.getFullName());
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),user.getStatus(),user.getRoles(),user.getCreatedAt());
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
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),user.getStatus(),user.getRoles(),user.getCreatedAt());
    }
}
