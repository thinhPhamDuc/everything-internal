package com.app.internal.user.entity;

import com.app.internal.booking.entity.Booking;
import com.app.internal.role.entity.Role;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "fullName", nullable = false, length = 50)
    private String fullName;

    @Column(name = "status", nullable = false, length = 50)
    private String status;

    // Task 3: thay cột "roles" String (luôn 1 giá trị) bằng quan hệ many-to-one
    // tới bảng Role thật. many-to-one (không phải many-to-many) vì JwtFilter/
    // JwtProvider vẫn đang giả định mỗi user có đúng 1 role - xem GIAO_AN Task 3.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Soft-delete: null = user còn hoạt động bình thường. Set giá trị này thay
    // vì DELETE thật để không phá FK của Booking (Booking.user tham chiếu tới
    // đúng row này) và giữ lại lịch sử booking sau khi tài khoản bị xoá.
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // LAZY (mặc định của @OneToMany) -> chỉ query bookings khi thật sự gọi
    // getBookings(), và bắt buộc phải còn Session mở tại thời điểm gọi đó.
    // Đây chính là field dùng để tái hiện LazyInitializationException.
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Booking> bookings;
}
