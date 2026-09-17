package com.app.playground.order;

import com.app.playground.order.event.PaymentCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bug #2 (Data Inconsistency tạm thời) sống ở đây: payment-service trả lời
 * "PAID" ngay lập tức (xem PaymentController.paySync/payAsync), nhưng
 * order-service chỉ thật sự đổi status sang CONFIRMED khi consumer này xử lý
 * xong event - có độ trễ (payment-service cấu hình
 * event-publish-delay-ms). Trong khoảng trễ đó, GET /orders/{id} vẫn trả về
 * PENDING_PAYMENT dù người dùng đã "thanh toán xong".
 */
@Component
public class PaymentCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedListener.class);

    private final OrderStore store;
    private final boolean propagateEnabled;

    public PaymentCompletedListener(OrderStore store,
                                     @Value("${tracing.propagate.enabled:false}") boolean propagateEnabled) {
        this.store = store;
        this.propagateEnabled = propagateEnabled;
    }

    @KafkaListener(topics = "payments.events", groupId = "order-service")
    public void onPaymentCompleted(
            PaymentCompletedEvent event,
            @Header(value = CorrelationIdFilter.HEADER, required = false) byte[] incomingCorrelationIdBytes) {
        String incoming = incomingCorrelationIdBytes == null
                ? null
                : new String(incomingCorrelationIdBytes, StandardCharsets.UTF_8);
        String correlationId = (propagateEnabled && incoming != null && !incoming.isBlank())
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            log.info("payment_completed_event_received order_id={} amount={} status={}",
                    event.orderId(), event.amount(), event.status());
            boolean updated = store.markConfirmed(event.orderId());
            log.info("order_status_updated_from_event order_id={} updated={} new_status=CONFIRMED",
                    event.orderId(), updated);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
