package com.app.internal.booking.payment;

// Cho phép ép kết quả thanh toán lúc gọi POST /bookings/{id}/pay?outcome=...
// - đúng tinh thần "sandbox" (giống thẻ test của Stripe/VNPay sandbox) để
// test được cả nhánh thành công lẫn thất bại một cách tất định, không phải
// chờ may rủi của random.
public enum PaymentOutcome {
    SUCCESS,
    FAIL
}
