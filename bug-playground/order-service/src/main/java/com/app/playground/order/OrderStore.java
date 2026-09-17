package com.app.playground.order;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Trước đây là ConcurrentHashMap trong RAM (mỗi instance order-service tự
 * giữ riêng, gây bug khi scale nhiều instance - request tạo order rơi vào
 * instance A, event Kafka xác nhận lại rơi vào instance B, B không thấy order
 * trong RAM của chính nó). Giờ chuyển sang MySQL DÙNG CHUNG qua
 * OrderRepository - instance nào xử lý cũng đọc/ghi cùng 1 nguồn, không còn
 * phụ thuộc "order được tạo ở đâu".
 */
@Component
public class OrderStore {

    private final OrderRepository repository;

    public OrderStore(OrderRepository repository) {
        this.repository = repository;
    }

    public Order create(String item, double amount) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Order order = new Order(id, item, amount, OrderStatus.PENDING_PAYMENT, Instant.now());
        return repository.save(order);
    }

    public Order find(String id) {
        return repository.findById(id).orElse(null);
    }

    @Transactional
    public boolean markConfirmed(String id) {
        return repository.findById(id).map(order -> {
            order.setStatus(OrderStatus.CONFIRMED);
            repository.save(order);
            return true;
        }).orElse(false);
    }
}
