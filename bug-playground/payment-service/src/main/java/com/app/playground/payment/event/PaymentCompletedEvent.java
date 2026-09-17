package com.app.playground.payment.event;

import java.time.Instant;

public record PaymentCompletedEvent(String orderId, double amount, String status, Instant occurredAt) {
}
