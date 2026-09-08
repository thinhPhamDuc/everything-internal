package com.app.internal.notification.mq;

import com.app.internal.notification.EmailService;
import com.app.internal.notification.dto.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserRegisteredEventListener {

    private final EmailService emailService;

    // Method này chạy trên thread pool RIÊNG của RabbitMQ listener container,
    // KHÔNG phải thread đang xử lý HTTP request /auth/register nữa -> nếu
    // gửi mail chậm (SMTP timeout...) cũng không làm chậm response trả về
    // client, và không làm dài thêm transaction đăng ký user.
    @RabbitListener(queues = RabbitMQConfig.QUEUE_USER_REGISTERED)
    public void handleUserRegistered(UserRegisteredEvent event) {
        log.info("[MQ] Consume UserRegisteredEvent userId={} trên thread={}",
                event.userId(), Thread.currentThread().getName());

        emailService.sendWelcomeEmail(event.email(), event.fullName());
    }
}
