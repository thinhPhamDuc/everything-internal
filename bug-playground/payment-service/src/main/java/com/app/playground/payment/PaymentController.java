package com.app.playground.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final ChaosState chaos;
    private final PaymentEventPublisher publisher;
    private final PricingService pricingService;

    public PaymentController(ChaosState chaos, PaymentEventPublisher publisher, PricingService pricingService) {
        this.chaos = chaos;
        this.publisher = publisher;
        this.pricingService = pricingService;
    }

    /**
     * Dùng cho kịch bản Bug #1 (Cascading Failure) - đợi ĐỦ latencyMs rồi mới
     * trả lời, mô phỏng payment-service/DB backend chậm.
     */
    @PostMapping("/{orderId}/pay-sync")
    public ResponseEntity<?> paySync(@PathVariable String orderId) throws InterruptedException {
        log.info("payment_sync_received order_id={}", orderId);
        if (chaos.isDown()) {
            log.warn("payment_sync_rejected order_id={} reason=chaos_down", orderId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
        long latency = chaos.getLatencyMs();
        if (latency > 0) {
            Thread.sleep(latency);
        }
        log.info("payment_sync_completed order_id={} latency_ms={}", orderId, latency);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "PAID", "latencyMs", latency));
    }

    /**
     * Dùng cho kịch bản Bug #2 (Data Inconsistency) - trả lời NGAY LẬP TỨC,
     * publish PaymentCompletedEvent với độ trễ riêng (eventPublishDelayMs).
     */
    @PostMapping("/{orderId}/pay-async")
    public ResponseEntity<?> payAsync(@PathVariable String orderId, @RequestBody(required = false) Map<String, Object> body) {
        double amount = (body != null && body.get("amount") != null) ? ((Number) body.get("amount")).doubleValue() : 0d;
        double chargedAmount = pricingService.applyLoyaltyDiscount(amount);
        log.info("payment_async_received order_id={} amount={} charged_amount={}", orderId, amount, chargedAmount);
        if (chaos.isDown()) {
            log.warn("payment_async_rejected order_id={} reason=chaos_down", orderId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
        long eventDelay = chaos.getEventPublishDelayMs();
        publisher.publishPaymentCompletedDelayed(orderId, chargedAmount, eventDelay);
        log.info("payment_async_ack_sent order_id={} event_publish_delay_ms={}", orderId, eventDelay);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "PAID", "chargedAmount", chargedAmount));
    }
}
