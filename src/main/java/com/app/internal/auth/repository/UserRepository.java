package com.app.internal.auth.repository;

import com.app.internal.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    // Dùng cho login: user đã bị soft-delete (deletedAt khác null) coi như
    // "không tồn tại" -> trả về Optional.empty(), AuthService.login() ném ra
    // đúng lỗi "Sai email hoặc mật khẩu" chung chung như trường hợp email
    // không tồn tại thật, không lộ thông tin tài khoản đã bị xoá.
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    // CỐ Ý giữ nguyên "existsByEmail" kiểm tra TRÊN TOÀN BỘ bảng (kể cả user
    // đã soft-delete), KHÔNG lọc deletedAt IS NULL. Lý do: cột "email" vẫn có
    // ràng buộc UNIQUE thật trên DB (xem User.java) áp dụng cho MỌI row, kể cả
    // row đã soft-delete. Nếu hàm này chỉ đếm user "còn sống", nó sẽ báo email
    // của 1 tài khoản đã xoá là "chưa dùng" -> AuthService.register() cho qua
    // -> save() thất bại thật vì vi phạm unique constraint -> 500 xấu xí thay
    // vì 409 sạch sẽ. Muốn cho phép đăng ký lại đúng email đã xoá, cần đổi
    // unique constraint trên DB thành partial unique index (WHERE deleted_at
    // IS NULL) trước - việc đó ngoài phạm vi soft-delete lần này.
    boolean existsByEmail(String email);

    Page<User> findByStatusAndEmailContainingIgnoreCase(String status, String email, Pageable pageable);

    // Dùng để chặn RoleService.deleteRole() xoá 1 role đang còn user tham
    // chiếu (User.role là nullable=false) - tránh lỗi FK 500 xấu xí.
    boolean existsByRoleId(Long roleId);
}
