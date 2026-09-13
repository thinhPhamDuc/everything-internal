package com.app.internal.sync.mq;

import com.app.internal.sync.client.ProviderFetchResult;
import com.app.internal.sync.client.ThirdPartyFlightFetchService;
import com.app.internal.sync.dto.BatchReadyEvent;
import com.app.internal.sync.dto.SyncTriggerEvent;
import com.app.internal.sync.mock.dto.MockFlightDto;
import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Consumer#1: nhận tín hiệu trigger -> fetch ĐỒNG THỜI tất cả provider (Task
// 9, xem ThirdPartyFlightFetchService) -> lưu staging (chỉ dữ liệu của
// provider fetch thành công) -> publish batchId sang chặng 2.
//
// Khác với trước Task 9: KHÔNG còn "tất cả hoặc không gì cả" ở bước fetch -
// 1/nhiều provider timeout/lỗi chỉ bị BỎ QUA (log lại), sync vẫn tiếp tục với
// dữ liệu của các provider còn thành công. Toàn bộ handler vẫn là 1 khối
// "tất cả hoặc không gì cả" Ở BƯỚC LƯU/PUBLISH: nếu lưu staging hoặc publish
// lỗi, ack mode mặc định (AUTO) tự reject message - RabbitMQ redeliver lại
// toàn bộ trigger, lần chạy lại sinh batchId MỚI (không "resume" batch cũ).
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightSyncTriggerListener {

    private final ThirdPartyFlightFetchService fetchService;
    private final FlightStagingRepository stagingRepository;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = SyncRabbitMQConfig.QUEUE_TRIGGER)
    public void handleTrigger(SyncTriggerEvent event) {
        log.info("[Sync] Consumer#1 nhận trigger triggeredAt={}", event.triggeredAt());

        List<ProviderFetchResult> results = fetchService.fetchAll();
        List<ProviderFetchResult> succeeded = results.stream().filter(ProviderFetchResult::success).toList();
        List<ProviderFetchResult> failed = results.stream().filter(r -> !r.success()).toList();

        failed.forEach(r -> log.warn("[Sync] Bỏ qua provider={}: {}", r.providerName(), r.errorMessage()));

        if (succeeded.isEmpty()) {
            log.error("[Sync] Cả {} provider đều thất bại - huỷ chu kỳ sync này, đợi lần trigger sau", results.size());
            return;
        }

        String batchId = UUID.randomUUID().toString();
        LocalDateTime fetchedAt = LocalDateTime.now();

        List<FlightStagingRecord> stagingRecords = succeeded.stream()
                .flatMap(result -> result.flights().stream()
                        .map(flight -> toStagingRecord(flight, result.providerName(), batchId, fetchedAt)))
                .toList();

        stagingRepository.saveAll(stagingRecords);
        log.info("[Sync] Consumer#1 lưu {} dòng staging (từ {}/{} provider thành công: {}), batchId={}",
                stagingRecords.size(), succeeded.size(), results.size(),
                succeeded.stream().map(ProviderFetchResult::providerName).toList(), batchId);

        rabbitTemplate.convertAndSend(SyncRabbitMQConfig.EXCHANGE, SyncRabbitMQConfig.ROUTING_KEY_BATCH,
                new BatchReadyEvent(batchId));
        log.info("[Sync] Consumer#1 publish batchId={} -> routingKey={}", batchId, SyncRabbitMQConfig.ROUTING_KEY_BATCH);
    }

    private FlightStagingRecord toStagingRecord(MockFlightDto flight, String provider, String batchId, LocalDateTime fetchedAt) {
        return FlightStagingRecord.builder()
                .batchId(batchId)
                .provider(provider)
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
