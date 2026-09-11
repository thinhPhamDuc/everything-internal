package com.app.internal.booking.payment;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// Mock gateway (chế độ sandbox) - GIAO_AN Task 7 cho phép "tự viết 1 mock
// gateway trả về thành công/thất bại giả lập" thay vì tích hợp VNPay/Momo
// sandbox thật. KHÔNG gọi ra ngoài, luôn trả kết quả ngay (đồng bộ) - vì đây
// đã là bước riêng biệt với bước giữ chỗ (BookingService.reserve), không vi
// phạm nguyên tắc "không xử lý thanh toán đồng bộ trong cùng transaction giữ
// chỗ" (2 bước đã tách bằng 2 API call khác nhau: POST /bookings rồi POST
// /bookings/{id}/pay).
@Component
public class PaymentGatewayClient {

    private static final int SUCCESS_RATE_PERCENT = 90;

    public PaymentResult charge(BigDecimal amount, PaymentOutcome forcedOutcome) {
        String transactionRef = "MOCK-" + UUID.randomUUID();

        boolean success = forcedOutcome != null
                ? forcedOutcome == PaymentOutcome.SUCCESS
                : ThreadLocalRandom.current().nextInt(100) < SUCCESS_RATE_PERCENT;

        return new PaymentResult(success, transactionRef);
    }
}
