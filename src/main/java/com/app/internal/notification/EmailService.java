package com.app.internal.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Mock: chỉ log ra console, chưa tích hợp SMTP thật. Đủ để demo luồng
// TransactionSynchronization mà không cần cấu hình mail server.
@Slf4j
@Service
public class EmailService {

    public void sendWelcomeEmail(String toEmail, String fullName) {
        log.info("[MOCK EMAIL] Đã gửi mail chào mừng tới {} <{}>", fullName, toEmail);
    }
}
