package com.app.playground.payment;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trạng thái "chaos" điều khiển được qua ChaosController lúc runtime - không
 * cần restart container để đổi kịch bản bug giữa các lần thử.
 */
@Component
public class ChaosState {

    private final AtomicLong latencyMs = new AtomicLong(0);
    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicLong eventPublishDelayMs = new AtomicLong(0);

    public long getLatencyMs() {
        return latencyMs.get();
    }

    public void setLatencyMs(long value) {
        latencyMs.set(value);
    }

    public boolean isDown() {
        return down.get();
    }

    public void setDown(boolean value) {
        down.set(value);
    }

    public long getEventPublishDelayMs() {
        return eventPublishDelayMs.get();
    }

    public void setEventPublishDelayMs(long value) {
        eventPublishDelayMs.set(value);
    }
}
