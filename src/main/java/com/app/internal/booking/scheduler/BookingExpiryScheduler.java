package com.app.internal.booking.scheduler;

import com.app.internal.booking.entity.Booking;
import com.app.internal.booking.enums.BookingStatus;
import com.app.internal.booking.repository.BookingRepository;
import com.app.internal.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

// GIAO_AN Task 7 bước 3: quét định kỳ các booking PENDING quá hạn giữ chỗ
// -> EXPIRED + hoàn availableSeats. Mỗi booking xử lý qua
// bookingService.expireBooking(id) - 1 @Transactional RIÊNG cho từng booking
// (không gộp cả batch vào 1 transaction) để 1 booking lỗi (VD đụng version
// optimistic lock với 1 admin đang sửa cùng lúc) không kéo rollback những
// booking khác đã xử lý xong trong cùng lượt quét; booking lỗi tự động được
// thử lại ở lượt quét kế tiếp vì nó vẫn còn PENDING.
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryScheduler {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    @Scheduled(cron = "${app.booking.expiry-cron:0 * * * * *}")
    public void expirePendingBookings() {
        List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, LocalDateTime.now());

        if (expired.isEmpty()) {
            return;
        }

        log.info("[BookingExpiryScheduler] Tìm thấy {} booking PENDING quá hạn, bắt đầu hoàn ghế", expired.size());

        for (Booking booking : expired) {
            try {
                bookingService.expireBooking(booking.getId());
            } catch (Exception e) {
                log.warn("[BookingExpiryScheduler] Lỗi khi expire bookingId={}, sẽ thử lại ở lượt quét kế tiếp",
                        booking.getId(), e);
            }
        }
    }
}
