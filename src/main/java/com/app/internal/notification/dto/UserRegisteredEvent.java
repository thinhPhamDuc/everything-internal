package com.app.internal.notification.dto;

import java.io.Serializable;

// Payload gửi qua RabbitMQ -> serialize/deserialize bằng Jackson2JsonMessageConverter
// (xem RabbitMQConfig), nên chỉ cần là 1 record/POJO bình thường.
public record UserRegisteredEvent(Long userId, String email, String fullName) implements Serializable {
}
