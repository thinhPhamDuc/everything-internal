package com.app.internal.user.service;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.UserNotFoundException;
import com.app.internal.user.entity.User;
import lombok.RequiredArgsConstructor;
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
}
