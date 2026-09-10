package com.app.internal.sync.scheduler;

import com.app.internal.sync.dto.SyncTriggerEvent;
import com.app.internal.sync.mq.SyncRabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlightSyncScheduler {

    private final RabbitTemplate rabbitTemplate;

    // Cron đọc từ application.properties (app.sync.cron) - lúc dev để mỗi vài
    // phút cho test nhanh, nhớ đổi lại "0 0 12 * * *" (12h trưa mỗi ngày)
    // trước khi coi Task 5 là "xong".
    @Scheduled(cron = "${app.sync.cron:0 0 12 * * *}")
    public void triggerSync() {
        SyncTriggerEvent event = new SyncTriggerEvent(Instant.now());

        log.info("[Sync] Publish trigger -> exchange={}, routingKey={}, triggeredAt={}",
                SyncRabbitMQConfig.EXCHANGE, SyncRabbitMQConfig.ROUTING_KEY_TRIGGER, event.triggeredAt());

        // convertAndSend() trả về ngay - Scheduler không đợi Consumer#1 fetch
        // xong mock API, đúng tinh thần bất đồng bộ của toàn luồng.
        rabbitTemplate.convertAndSend(SyncRabbitMQConfig.EXCHANGE, SyncRabbitMQConfig.ROUTING_KEY_TRIGGER, event);
    }
}
