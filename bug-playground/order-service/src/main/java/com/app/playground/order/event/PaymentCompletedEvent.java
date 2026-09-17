package com.app.playground.order.event;

import java.time.Instant;

/**
 * Bản sao độc lập của event bên payment-service (2 service KHÔNG dùng chung
 * class/module - đúng ranh giới microservices, mỗi service tự định nghĩa
 * "hợp đồng" nó hiểu). Field phải khớp tên với payment-service để
 * JsonDeserializer map đúng - lệch tên/JSON shape ở đây chính là hạt giống
 * của bug "Breaking Changes / Contract Violation" (Nhóm 2, chưa làm ở lượt này).
 */
public record PaymentCompletedEvent(String orderId, double amount, String status, Instant occurredAt) {
}
