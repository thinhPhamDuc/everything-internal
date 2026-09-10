package com.app.internal.batch.processor;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.sync.staging.entity.FlightStagingRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Trả về null khi dữ liệu thô lỗi -> Spring Batch tự bỏ qua item null,
// không throw làm fail cả chunk (các dòng hợp lệ khác trong cùng chunk vẫn
// được import bình thường).
@Slf4j
@Component
public class FlightStagingProcessor implements ItemProcessor<FlightStagingRecord, FlightTicketInventory> {

    @Override
    public FlightTicketInventory process(FlightStagingRecord raw) {
        if (raw.getRawPrice() == null || raw.getRawPrice().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[Batch] Reject staging id={}: giá không hợp lệ ({})", raw.getId(), raw.getRawPrice());
            return null;
        }
        if (raw.getRawSeatsLeft() == null || raw.getRawSeatsLeft() < 0) {
            log.warn("[Batch] Reject staging id={}: seatsLeft không hợp lệ ({})", raw.getId(), raw.getRawSeatsLeft());
            return null;
        }
        if (raw.getRawDepartureTime() == null || !raw.getRawDepartureTime().isAfter(LocalDateTime.now())) {
            log.warn("[Batch] Reject staging id={}: departureTime không hợp lệ ({})", raw.getId(), raw.getRawDepartureTime());
            return null;
        }

        SeatClass seatClass = parseSeatClass(raw);
        if (seatClass == null) {
            return null;
        }

        // totalSeats/availableSeats CÙNG lấy từ seatsLeft của mock - mock chỉ
        // biết "còn bao nhiêu ghế", không có khái niệm "tổng ghế" riêng.
        // id/createdAt/lastSyncedAt/version CỐ Ý để trống - Writer (Bước 9)
        // mới là nơi quyết định đây là INSERT (tự điền) hay UPDATE (giữ
        // nguyên id/createdAt cũ, chỉ lấy price/availableSeats từ object này).
        return FlightTicketInventory.builder()
                .flightCode(raw.getRawFlightCode())
                .airline(raw.getRawAirline())
                .origin(raw.getRawOrigin())
                .destination(raw.getRawDestination())
                .departureTime(raw.getRawDepartureTime())
                .arrivalTime(raw.getRawArrivalTime())
                .seatClass(seatClass)
                .price(raw.getRawPrice())
                .totalSeats(raw.getRawSeatsLeft())
                .availableSeats(raw.getRawSeatsLeft())
                .status(InventoryStatus.OPEN)
                .sourceSystem("SYNC")
                .build();
    }

    private SeatClass parseSeatClass(FlightStagingRecord raw) {
        try {
            return SeatClass.valueOf(raw.getRawSeatClass());
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warn("[Batch] Reject staging id={}: seatClass không hợp lệ ({})", raw.getId(), raw.getRawSeatClass());
            return null;
        }
    }
}
