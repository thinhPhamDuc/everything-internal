package com.app.internal.booking.entity;

import com.app.internal.booking.enums.BookingStatus;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.user.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Task 7: đặt vé + thanh toán. Ghế bị trừ trên FlightTicketInventory ngay
// lúc tạo Booking status PENDING (BookingService.reserve, xem
// InventoryService.decrementSeats) - KHÔNG đợi thanh toán xong, để tránh 2
// người cùng đặt vé cuối cùng (GIAO_AN Task 7 bước 2).
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_id", nullable = false)
    private FlightTicketInventory inventory;

    @Column(name = "passenger_count", nullable = false)
    private Integer passengerCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    // = inventory.price * passengerCount tại thời điểm đặt - lưu lại thay vì
    // tính lại mỗi lần đọc, vì giá vé có thể đổi sau đó (admin sửa giá) mà
    // KHÔNG được ảnh hưởng ngược tới booking đã tạo.
    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Chỉ có ý nghĩa khi status = PENDING - BookingExpiryScheduler quét các
    // booking PENDING có expiresAt < now để tự huỷ + hoàn ghế (GIAO_AN Task 7
    // bước 3).
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}
