package com.app.internal.booking.service;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.booking.dto.BookingCreateRequest;
import com.app.internal.booking.dto.BookingResponse;
import com.app.internal.booking.entity.Booking;
import com.app.internal.booking.entity.Payment;
import com.app.internal.booking.enums.BookingStatus;
import com.app.internal.booking.enums.PaymentStatus;
import com.app.internal.booking.payment.PaymentGatewayClient;
import com.app.internal.booking.payment.PaymentOutcome;
import com.app.internal.booking.payment.PaymentResult;
import com.app.internal.booking.repository.BookingRepository;
import com.app.internal.booking.repository.PaymentRepository;
import com.app.internal.common.exception.BookingNotFoundException;
import com.app.internal.common.exception.InvalidBookingStateException;
import com.app.internal.common.exception.UserNotFoundException;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.service.InventoryService;
import com.app.internal.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String PROVIDER = "MOCK_GATEWAY";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final PaymentGatewayClient paymentGatewayClient;

    @Value("${app.booking.hold-minutes:15}")
    private int holdMinutes;

    // "Trừ ghế + tạo booking" nằm CHUNG 1 @Transactional (GIAO_AN Task 7 lưu ý)
    // - inventoryService.decrementSeats() bên dưới KHÔNG mở transaction mới
    // (propagation REQUIRED mặc định), chỉ join vào transaction này. Nếu
    // decrementSeats() ném SeatUnavailableException, toàn bộ transaction -
    // kể cả UPDATE trừ ghế đã flush - bị rollback, không để lại Booking mồ côi.
    @Transactional
    public BookingResponse reserve(Long userId, BookingCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Không tìm thấy user với id=" + userId));

        FlightTicketInventory inventory =
                inventoryService.decrementSeats(request.inventoryId(), request.passengerCount());

        BigDecimal totalPrice = inventory.getPrice().multiply(BigDecimal.valueOf(request.passengerCount()));

        Booking booking = Booking.builder()
                .user(user)
                .inventory(inventory)
                .passengerCount(request.passengerCount())
                .status(BookingStatus.PENDING)
                .totalPrice(totalPrice)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(holdMinutes))
                .build();

        return toResponse(bookingRepository.save(booking));
    }

    // forcedOutcome != null -> ép kết quả (test tất định); null -> gateway tự
    // random (xem PaymentGatewayClient).
    @Transactional
    public BookingResponse pay(Long userId, Long bookingId, PaymentOutcome forcedOutcome) {
        Booking booking = findOwnedOrThrow(bookingId, userId);

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new InvalidBookingStateException(
                    "Booking id=" + bookingId + " đang ở trạng thái " + booking.getStatus() + ", không thể thanh toán");
        }

        // Vừa hết hạn giữ chỗ nhưng scheduler chưa kịp quét tới - chặn thanh
        // toán ngay tại đây thay vì để user thanh toán "thành công" cho 1
        // booking đã hết hạn. CỐ Ý KHÔNG tự expire+hoàn ghế ngay tại đây: nếu
        // làm vậy trong CÙNG transaction rồi ném exception bên dưới, toàn bộ
        // transaction (kể cả phần hoàn ghế) sẽ bị rollback theo - phải để
        // nguyên PENDING, BookingExpiryScheduler sẽ tự dọn trong tick kế tiếp
        // (tối đa ~1 phút sau, chạy trong transaction RIÊNG của nó).
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidBookingStateException(
                    "Booking id=" + bookingId + " đã hết hạn giữ chỗ, ghế sẽ được tự động hoàn lại trong giây lát");
        }

        PaymentResult result = paymentGatewayClient.charge(booking.getTotalPrice(), forcedOutcome);

        Payment payment = Payment.builder()
                .booking(booking)
                .provider(PROVIDER)
                .status(result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED)
                .transactionRef(result.transactionRef())
                .amount(booking.getTotalPrice())
                .paidAt(result.success() ? LocalDateTime.now() : null)
                .build();
        paymentRepository.save(payment);

        if (result.success()) {
            // Ghế đã trừ từ lúc reserve() - đây chỉ là xác nhận final, KHÔNG
            // trừ lại lần nữa (GIAO_AN Task 7 bước 5).
            booking.setStatus(BookingStatus.CONFIRMED);
        } else {
            booking.setStatus(BookingStatus.CANCELLED);
            inventoryService.incrementSeats(booking.getInventory().getId(), booking.getPassengerCount());
        }

        log.info("[Booking] pay bookingId={}, provider={}, transactionRef={}, success={}",
                bookingId, PROVIDER, result.transactionRef(), result.success());

        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> listMyBookings(Long userId, Pageable pageable) {
        return bookingRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public BookingResponse getMyBookingById(Long userId, Long bookingId) {
        return toResponse(findOwnedOrThrow(bookingId, userId));
    }

    // Gọi bởi BookingExpiryScheduler - id đến từ chính
    // findByStatusAndExpiresAtBefore(PENDING, now) nên không cần check lại
    // status/expiresAt ở tầng gọi, nhưng vẫn tự re-check ở đây (idempotent,
    // phòng trường hợp user vừa pay() thành công giữa lúc scheduler đang quét).
    @Transactional
    public void expireBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy booking với id=" + bookingId));

        if (booking.getStatus() != BookingStatus.PENDING) {
            return;
        }

        expireAndReleaseSeats(booking);
    }

    private void expireAndReleaseSeats(Booking booking) {
        booking.setStatus(BookingStatus.EXPIRED);
        inventoryService.incrementSeats(booking.getInventory().getId(), booking.getPassengerCount());
    }

    private Booking findOwnedOrThrow(Long bookingId, Long userId) {
        return bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException("Không tìm thấy booking với id=" + bookingId));
    }

    private BookingResponse toResponse(Booking booking) {
        FlightTicketInventory inventory = booking.getInventory();
        return new BookingResponse(
                booking.getId(),
                inventory.getId(),
                inventory.getFlightCode(),
                inventory.getAirline(),
                inventory.getOrigin(),
                inventory.getDestination(),
                inventory.getDepartureTime(),
                inventory.getSeatClass().name(),
                booking.getPassengerCount(),
                booking.getTotalPrice(),
                booking.getStatus().name(),
                booking.getCreatedAt(),
                booking.getExpiresAt());
    }
}
