package com.app.playground.order;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity - lưu vào MySQL DÙNG CHUNG cho mọi instance order-service, thay
 * vì ConcurrentHashMap trong RAM riêng từng instance (bug đã gặp khi scale).
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;
    private String item;
    private double amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant createdAt;

    protected Order() {
        // JPA cần constructor rỗng
    }

    public Order(String id, String item, double amount, OrderStatus status, Instant createdAt) {
        this.id = id;
        this.item = item;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public double getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
