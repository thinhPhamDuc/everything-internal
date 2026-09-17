package com.app.playground.payment;

import org.springframework.stereotype.Component;

/**
 * Chính sách ưu đãi khách hàng thân thiết: đơn từ 500.000đ trở lên được giảm
 * 10% khi thanh toán.
 */
@Component
public class PricingService {

    private static final double LOYALTY_THRESHOLD = 500_000;
    private static final double LOYALTY_RATE = 0.10;

    public double applyLoyaltyDiscount(double amount) {
        if (amount >= LOYALTY_THRESHOLD) {
            double discount = amount * LOYALTY_RATE;
            return amount - discount;
        }
        return amount;
    }
}
