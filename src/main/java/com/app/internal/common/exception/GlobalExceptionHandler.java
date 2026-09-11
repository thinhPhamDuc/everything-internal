package com.app.internal.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest()
                .body(new ApiError(Instant.now(), 400, "Dữ liệu không hợp lệ", details));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(Instant.now(), 401, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(Instant.now(), 404, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ApiError> handleRoleNotFound(RoleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(Instant.now(), 404, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(DuplicateRoleNameException.class)
    public ResponseEntity<ApiError> handleDuplicateRoleName(DuplicateRoleNameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(RoleInUseException.class)
    public ResponseEntity<ApiError> handleRoleInUse(RoleInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<ApiError> handleInventoryNotFound(InventoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(Instant.now(), 404, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(DuplicateInventoryException.class)
    public ResponseEntity<ApiError> handleDuplicateInventory(DuplicateInventoryException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(InvalidSeatCountException.class)
    public ResponseEntity<ApiError> handleInvalidSeatCount(InvalidSeatCountException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(Instant.now(), 400, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(Instant.now(), 400, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ApiError> handleBookingNotFound(BookingNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(Instant.now(), 404, ex.getMessage(), List.of()));
    }

    // 409 (không phải 400) - đây không phải lỗi input sai, mà là xung đột
    // trạng thái tại thời điểm request tới (ghế vừa bị người khác đặt hết) -
    // client có thể thử lại hợp lý, khác với lỗi input cần sửa mới gọi lại được.
    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ApiError> handleSeatUnavailable(SeatUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(InventoryNotAvailableException.class)
    public ResponseEntity<ApiError> handleInventoryNotAvailable(InventoryNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }

    @ExceptionHandler(InvalidBookingStateException.class)
    public ResponseEntity<ApiError> handleInvalidBookingState(InvalidBookingStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(Instant.now(), 409, ex.getMessage(), List.of()));
    }
}
