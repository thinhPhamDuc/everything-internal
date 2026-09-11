package com.app.internal.booking.controller;

import com.app.internal.booking.dto.BookingCreateRequest;
import com.app.internal.booking.dto.BookingResponse;
import com.app.internal.booking.payment.PaymentOutcome;
import com.app.internal.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// KHÔNG @PreAuthorize riêng - mọi endpoint ở đây chỉ cần authenticated()
// (đã là default "anyRequest().authenticated()" ở SecurityConfig), vì đặt vé
// là chức năng của MỌI user đã đăng nhập, không phân biệt role/permission.
// userId luôn lấy từ chính token đang gọi (Authentication.getPrincipal(),
// xem JwtFilter) - KHÔNG bao giờ nhận userId từ request body/param, để 1 user
// không thể đặt vé/xem booking thay mặt user khác.
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<BookingResponse> reserve(
            Authentication authentication, @Valid @RequestBody BookingCreateRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED).body(bookingService.reserve(userId, request));
    }

    // outcome: tuỳ chọn, ép kết quả thanh toán để test tất định (xem
    // PaymentOutcome) - bỏ trống thì PaymentGatewayClient tự random.
    @PostMapping("/{id}/pay")
    public ResponseEntity<BookingResponse> pay(
            Authentication authentication,
            @PathVariable("id") Long id,
            @RequestParam(required = false) PaymentOutcome outcome) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(bookingService.pay(userId, id, outcome));
    }

    @GetMapping("/me")
    public ResponseEntity<Page<BookingResponse>> myBookings(
            Authentication authentication,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(bookingService.listMyBookings(userId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(Authentication authentication, @PathVariable("id") Long id) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(bookingService.getMyBookingById(userId, id));
    }
}
