package com.app.internal.notification.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "app.events.exchange";
    public static final String QUEUE_USER_REGISTERED = "user.registered.queue";
    public static final String ROUTING_KEY_USER_REGISTERED = "user.registered";

    @Bean
    public TopicExchange appEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return new Queue(QUEUE_USER_REGISTERED, true); // durable = true: queue sống sót qua restart RabbitMQ
    }

    @Bean
    public Binding userRegisteredBinding(Queue userRegisteredQueue, TopicExchange appEventsExchange) {
        return BindingBuilder.bind(userRegisteredQueue).to(appEventsExchange).with(ROUTING_KEY_USER_REGISTERED);
    }

    // Spring Boot tự động dùng bean MessageConverter này cho RabbitTemplate
    // được autoconfigure sẵn -> message được serialize thành JSON thay vì
    // Java serialization mặc định (khó đọc, dễ vỡ khi đổi version class).
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
