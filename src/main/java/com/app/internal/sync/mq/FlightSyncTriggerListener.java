package com.app.internal.sync.mq;

import com.app.internal.sync.dto.BatchReadyEvent;
import com.app.internal.sync.dto.SyncTriggerEvent;
import com.app.internal.sync.mock.dto.MockFlightDto;
import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Consumer#1: nhận tín hiệu trigger -> gọi mock API bên thứ 3 -> lưu staging
// -> publish batchId sang chặng 2. Toàn bộ handler là 1 khối "tất cả hoặc
// không gì cả": nếu bất kỳ bước nào ném exception, ack mode mặc định của
// Spring (AUTO) tự reject message - RabbitMQ redeliver lại toàn bộ trigger,
// lần chạy lại sinh batchId MỚI (không "resume" batch cũ, xem giải thích
// idempotency đã thống nhất ở Bước 0/9). Manual ack (Channel + deliveryTag)
// là nâng cấp có thể làm sau, không bắt buộc cho MVP.
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightSyncTriggerListener {

    private final RestClient restClient;
    private final FlightStagingRepository stagingRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${app.sync.mock-api-url}")
    private String mockApiUrl;

    @RabbitListener(queues = SyncRabbitMQConfig.QUEUE_TRIGGER)
    public void handleTrigger(SyncTriggerEvent event) {
        log.info("[Sync] Consumer#1 nhận trigger triggeredAt={}", event.triggeredAt());

        List<MockFlightDto> flights = restClient.get()
                .uri(mockApiUrl)
                .retrieve()
                .body(new ParameterizedTypeReference<List<MockFlightDto>>() {
                });

        String batchId = UUID.randomUUID().toString();
        LocalDateTime fetchedAt = LocalDateTime.now();

        List<FlightStagingRecord> stagingRecords = flights.stream()
                .map(flight -> toStagingRecord(flight, batchId, fetchedAt))
                .toList();

        stagingRepository.saveAll(stagingRecords);
        log.info("[Sync] Consumer#1 lưu {} dòng staging, batchId={}", stagingRecords.size(), batchId);

        rabbitTemplate.convertAndSend(SyncRabbitMQConfig.EXCHANGE, SyncRabbitMQConfig.ROUTING_KEY_BATCH,
                new BatchReadyEvent(batchId));
        log.info("[Sync] Consumer#1 publish batchId={} -> routingKey={}", batchId, SyncRabbitMQConfig.ROUTING_KEY_BATCH);
    }

    private FlightStagingRecord toStagingRecord(MockFlightDto flight, String batchId, LocalDateTime fetchedAt) {
        return FlightStagingRecord.builder()
                .batchId(batchId)
                .rawFlightCode(flight.flightCode())
                .rawAirline(flight.airline())
                .rawOrigin(flight.origin())
                .rawDestination(flight.destination())
                .rawDepartureTime(flight.departureTime())
                .rawArrivalTime(flight.arrivalTime())
                .rawSeatClass(flight.seatClass())
                .rawPrice(flight.price())
                .rawSeatsLeft(flight.seatsLeft())
                .fetchedAt(fetchedAt)
                .processed(false)
                .build();
    }
}
