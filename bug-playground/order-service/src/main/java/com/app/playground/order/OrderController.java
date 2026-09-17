package com.app.playground.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderStore store;
    private final PaymentClient paymentClient;

    public OrderController(OrderStore store, PaymentClient paymentClient) {
        this.store = store;
        this.paymentClient = paymentClient;
    }

    public record CreateOrderRequest(String item, double amount) {
    }

    @PostMapping
    public ResponseEntity<Order> create(@RequestBody CreateOrderRequest request) {
        Order order = store.create(request.item(), request.amount());
        log.info("order_created order_id={} item={} amount={}", order.getId(), order.getItem(), order.getAmount());
        return ResponseEntity.ok(order);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> get(@PathVariable String id) {
        Order order = store.find(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        log.info("order_status_queried order_id={} status={}", id, order.getStatus());
        return ResponseEntity.ok(order);
    }

    /**
     * Bug #1 (Cascading Failure) demo entrypoint - gọi ĐỒNG BỘ sang
     * payment-service và chờ (xem PaymentClient). Cũng dùng lại được cho demo
     * Bug #3 (Tracing) vì đi qua đủ 3 chặng gateway -> order -> payment.
     */
    @PostMapping("/{id}/checkout-sync")
    public ResponseEntity<?> checkoutSync(@PathVariable String id) {
        Order order = store.find(id);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        log.info("checkout_sync_started order_id={}", id);
        String paymentResult = paymentClient.chargeSync(id, order.getAmount());
        log.info("checkout_sync_finished order_id={}", id);
        return ResponseEntity.ok(Map.of("orderId", id, "paymentResult", paymentResult));
    }
}
