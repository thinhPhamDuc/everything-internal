package com.app.internal.booking.entity;

import com.app.internal.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

// Bảng con tối thiểu chỉ để phục vụ việc test LazyInitializationException ở
// UserLazyLoadingTest — chưa phải entity Booking đầy đủ của Task 7 (chưa có
// Inventory, Payment, status PENDING/CONFIRMED...).
@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "flight_code", nullable = false, length = 20)
    private String flightCode;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
