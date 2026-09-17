package com.app.playground.payment;

import com.app.playground.payment.event.PaymentCompletedEvent;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bug #2 (Data Inconsistency tạm thời): publish CỐ Ý bị delay
 * (eventPublishDelayMs, xem ChaosState) trong khi HTTP response ở
 * PaymentController đã trả "PAID" ngay lập tức - đúng bản chất
 * event-driven "eventual consistency", chỉ là ở đây độ trễ được phóng to để
 * dễ quan sát bằng mắt (giây thay vì mili-giây thật ngoài production).
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);
    private static final String TOPIC = "payments.events";

    private final KafkaTemplate<String, PaymentCompletedEvent> kafkaTemplate;
    private final boolean propagateEnabled;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public PaymentEventPublisher(KafkaTemplate<String, PaymentCompletedEvent> kafkaTemplate,
                                  @Value("${tracing.propagate.enabled:false}") boolean propagateEnabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.propagateEnabled = propagateEnabled;
    }

    public void publishPaymentCompletedDelayed(String orderId, double amount, long delayMs) {
        // MDC KHÔNG tự kế thừa sang thread khác - phải đọc value ngay trên
        // request thread rồi truyền tay vào task chạy ở scheduler thread.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        Runnable task = () -> doPublish(orderId, amount, correlationId);
        if (delayMs <= 0) {
            task.run();
        } else {
            scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void doPublish(String orderId, double amount, String correlationId) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(orderId, amount, "PAID", Instant.now());
        ProducerRecord<String, PaymentCompletedEvent> record = new ProducerRecord<>(TOPIC, orderId, event);
        if (propagateEnabled && correlationId != null) {
            record.headers().add(CorrelationIdFilter.HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        log.info("payment_completed_event_publishing order_id={} correlation_forwarded={}",
                orderId, propagateEnabled && correlationId != null);
        kafkaTemplate.send(record);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
