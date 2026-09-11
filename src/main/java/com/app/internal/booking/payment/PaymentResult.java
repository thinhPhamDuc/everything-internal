package com.app.internal.booking.payment;

public record PaymentResult(boolean success, String transactionRef) {
}
