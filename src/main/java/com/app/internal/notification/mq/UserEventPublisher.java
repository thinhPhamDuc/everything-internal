package com.app.internal.notification.mq;

import com.app.internal.notification.dto.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishUserRegistered(UserRegisteredEvent event) {
        log.info("[MQ] Publish UserRegisteredEvent userId={} -> exchange={}, routingKey={}",
                event.userId(), RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY_USER_REGISTERED);

        // convertAndSend() trả về NGAY sau khi đẩy message vào RabbitMQ, không
        // đợi consumer xử lý xong -> đây chính là điểm "bất đồng bộ" so với
        // gọi thẳng emailService.sendWelcomeEmail() như code cũ.
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY_USER_REGISTERED, event);
    }
}
