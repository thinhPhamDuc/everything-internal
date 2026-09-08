package com.app.internal.user.entity;

import com.app.internal.booking.entity.Booking;
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

    @Column(name = "roles", nullable = false, length = 50)
    private String roles;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // LAZY (mặc định của @OneToMany) -> chỉ query bookings khi thật sự gọi
    // getBookings(), và bắt buộc phải còn Session mở tại thời điểm gọi đó.
    // Đây chính là field dùng để tái hiện LazyInitializationException.
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Booking> bookings;
}
