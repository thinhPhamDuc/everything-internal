package com.app.internal.booking.enums;

public enum BookingStatus {
    // Vừa giữ chỗ, ghế đã bị trừ trên Inventory nhưng chưa thanh toán xong.
    PENDING,
    // Thanh toán thành công - ghế đã trừ từ lúc PENDING, đây chỉ là xác nhận
    // final, KHÔNG trừ lại lần nữa (xem GIAO_AN Task 7 bước 5).
    CONFIRMED,
    // Thanh toán thất bại, hoặc user tự huỷ khi còn PENDING - ghế đã hoàn lại.
    CANCELLED,
    // Quá 15 phút vẫn PENDING mà chưa thanh toán - BookingExpiryScheduler tự
    // chuyển sang trạng thái này, ghế đã hoàn lại.
    EXPIRED
}
