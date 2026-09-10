package com.app.internal.sync.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 1 exchange dùng chung, 2 routing key khác nhau cho 2 chặng của luồng sync
// (đúng gợi ý GIAO_AN.md). MessageConverter JSON đã khai báo sẵn ở
// notification/mq/RabbitMQConfig - dùng chung, KHÔNG khai báo lại ở đây
// (2 bean cùng kiểu MessageConverter sẽ phá autoconfigure của RabbitTemplate).
@Configuration
public class SyncRabbitMQConfig {

    public static final String EXCHANGE = "app.sync.exchange";
    public static final String QUEUE_TRIGGER = "flight.sync.trigger";
    public static final String QUEUE_BATCH = "flight.batch.trigger";
    public static final String ROUTING_KEY_TRIGGER = "sync.trigger";
    public static final String ROUTING_KEY_BATCH = "sync.batch";

    @Bean
    public TopicExchange syncExchange() {
        return new TopicExchange(EXCHANGE);
    }

    // ---- Chặng 1: Scheduler -> Consumer#1 (fetch mock API) ----

    @Bean
    public Queue flightSyncTriggerQueue() {
        return QueueBuilder.durable(QUEUE_TRIGGER)
                // "" = default exchange - tự route message lỗi tới đúng queue
                // có tên trùng routing-key, không cần khai báo binding riêng.
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_TRIGGER + ".dlq")
                .build();
    }

    @Bean
    public Queue flightSyncTriggerDlq() {
        return QueueBuilder.durable(QUEUE_TRIGGER + ".dlq").build();
    }

    @Bean
    public Binding flightSyncTriggerBinding(Queue flightSyncTriggerQueue, TopicExchange syncExchange) {
        return BindingBuilder.bind(flightSyncTriggerQueue).to(syncExchange).with(ROUTING_KEY_TRIGGER);
    }

    // ---- Chặng 2: Consumer#1 -> Consumer#2 (JobLauncher) ----

    @Bean
    public Queue flightBatchTriggerQueue() {
        return QueueBuilder.durable(QUEUE_BATCH)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE_BATCH + ".dlq")
                .build();
    }

    @Bean
    public Queue flightBatchTriggerDlq() {
        return QueueBuilder.durable(QUEUE_BATCH + ".dlq").build();
    }

    @Bean
    public Binding flightBatchTriggerBinding(Queue flightBatchTriggerQueue, TopicExchange syncExchange) {
        return BindingBuilder.bind(flightBatchTriggerQueue).to(syncExchange).with(ROUTING_KEY_BATCH);
    }
}
