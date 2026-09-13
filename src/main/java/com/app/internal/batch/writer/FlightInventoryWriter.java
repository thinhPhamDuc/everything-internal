package com.app.internal.batch.writer;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

// Phần quan trọng nhất Task 5: tự findBy... rồi quyết định insert/update,
// KHÔNG dùng save() để JPA tự "upsert" - save() chỉ insert-vs-update dựa
// theo id đã có hay chưa, mà dữ liệu từ staging không có id Inventory, chỉ
// có tổ hợp unique key nghiệp vụ (flightCode + departureTime + seatClass +
// provider - đã thêm "provider" ở Task 9, xem FlightTicketInventory).
//
// Đánh dấu FlightStagingRecord.processed=true CỐ Ý KHÔNG làm ở đây theo
// từng dòng (xem batch/job/FlightImportJobConfig - có 1 Step riêng chạy
// SAU khi cả flightImportStep COMPLETED, bulk update processed=true cho cả
// batchId 1 lần). Lý do: Writer không có id của FlightStagingRecord gốc
// (Processor trả FlightTicketInventory, không mang theo id staging) - tách
// việc đánh dấu ra 1 Step riêng, chạy sau khi TOÀN BỘ Step ghi Inventory
// thành công, để tránh nguy cơ đánh dấu processed=true trước khi chắc chắn
// dữ liệu đã nằm trong Inventory (mất dữ liệu nếu ngược thứ tự).
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightInventoryWriter implements ItemWriter<FlightTicketInventory> {

    private final InventoryRepository inventoryRepository;

    @Override
    public void write(Chunk<? extends FlightTicketInventory> chunk) {
        LocalDateTime now = LocalDateTime.now();

        for (FlightTicketInventory incoming : chunk) {
            Optional<FlightTicketInventory> existing = inventoryRepository
                    .findByFlightCodeAndDepartureTimeAndSeatClassAndProvider(
                            incoming.getFlightCode(), incoming.getDepartureTime(), incoming.getSeatClass(),
                            incoming.getProvider());

            if (existing.isPresent()) {
                // UPDATE: chỉ cập nhật field "sống" - KHÔNG động vào
                // id/status/totalSeats hiện tại. VD admin đã tay CLOSED 1 vé
                // (Task 4) -> sync không tự mở lại; totalSeats là công suất
                // do admin quản lý, sync không ghi đè.
                FlightTicketInventory current = existing.get();
                current.setPrice(incoming.getPrice());
                current.setAvailableSeats(incoming.getAvailableSeats());
                current.setLastSyncedAt(now);
                inventoryRepository.save(current);
            } else {
                // INSERT mới - sourceSystem="SYNC" đã set từ Processor.
                incoming.setCreatedAt(now);
                incoming.setLastSyncedAt(now);
                inventoryRepository.save(incoming);
            }
        }

        log.info("[Batch] Writer upsert {} dòng vào Inventory", chunk.size());
    }
}
