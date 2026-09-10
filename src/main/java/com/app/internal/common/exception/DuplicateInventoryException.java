package com.app.internal.common.exception;

// Ném khi tạo mới 1 vé trùng tổ hợp (flightCode + departureTime + seatClass)
// với vé đã có - check trước ở tầng Service thay vì để DB tự ném
// DataIntegrityViolationException, để trả lỗi nghiệp vụ rõ ràng (409) thay vì
// lỗi kỹ thuật tầng DB.
public class DuplicateInventoryException extends RuntimeException {

    public DuplicateInventoryException(String message) {
        super(message);
    }
}
