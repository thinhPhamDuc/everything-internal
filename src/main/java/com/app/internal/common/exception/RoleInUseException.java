package com.app.internal.common.exception;

// Ném khi cố xoá 1 Role đang có User tham chiếu - User.role là nullable=false
// nên xoá thẳng sẽ vỡ ràng buộc khoá ngoại; chặn sớm ở tầng service để trả về
// lỗi nghiệp vụ rõ ràng thay vì lỗi FK 500 xấu xí từ tầng DB.
public class RoleInUseException extends RuntimeException {

    public RoleInUseException(String message) {
        super(message);
    }
}
