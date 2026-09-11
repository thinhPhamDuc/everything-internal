package com.app.internal.booking.repository;

import com.app.internal.booking.entity.Booking;
import com.app.internal.booking.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Page<Booking> findByUserId(Long userId, Pageable pageable);

    // Dùng cho cả GET /bookings/{id} lẫn POST /bookings/{id}/pay - không tìm
    // thấy (id sai HOẶC không phải chủ booking) đều trả Optional.empty(), ném
    // chung 1 BookingNotFoundException - không lộ cho user A biết booking id
    // đó có tồn tại (thuộc về user B) hay không, giống nguyên tắc login ở
    // AuthService.
    Optional<Booking> findByIdAndUserId(Long id, Long userId);

    // BookingExpiryScheduler quét - KHÔNG cần Pageable, số lượng PENDING quá
    // hạn tại 1 thời điểm luôn nhỏ (tick mỗi phút), khác hẳn khối lượng của
    // InventoryRepository.searchAvailable.
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, LocalDateTime cutoff);
}
