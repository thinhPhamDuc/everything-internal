package com.app.internal.common.exception;

// Ném khi vi phạm ràng buộc nghiệp vụ availableSeats <= totalSeats (hoặc âm)
// - VD giảm totalSeats xuống dưới availableSeats đang có.
public class InvalidSeatCountException extends RuntimeException {

    public InvalidSeatCountException(String message) {
        super(message);
    }
}
