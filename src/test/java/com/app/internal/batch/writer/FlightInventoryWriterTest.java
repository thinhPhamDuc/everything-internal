package com.app.internal.batch.writer;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Gọi thẳng writer.write(...) với 1 Chunk giả (không cần chạy cả Job) -
// đúng gợi ý "tự kiểm tra" của TASK5.md Bước 9.
@SpringBootTest
class FlightInventoryWriterTest {

    @Autowired
    private InventoryRepository inventoryRepository;

    private FlightInventoryWriter writer;
    private List<Long> createdIds;

    @BeforeEach
    void setUp() {
        writer = new FlightInventoryWriter(inventoryRepository);
    }

    @AfterEach
    void tearDown() {
        if (createdIds != null) {
            createdIds.forEach(id -> inventoryRepository.findById(id).ifPresent(inventoryRepository::delete));
        }
    }

    @Test
    void write_insertMoiKhiChuaCoDungUniqueKey() throws Exception {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(3).withNano(0);
        String flightCode = "SYNC-" + System.nanoTime();

        FlightTicketInventory candidate = FlightTicketInventory.builder()
                .flightCode(flightCode)
                .airline("Test Airline")
                .origin("HAN")
                .destination("SGN")
                .departureTime(departureTime)
                .arrivalTime(departureTime.plusHours(2))
                .seatClass(SeatClass.ECONOMY)
                .price(new BigDecimal("1000000"))
                .totalSeats(50)
                .availableSeats(50)
                .status(InventoryStatus.OPEN)
                .sourceSystem("SYNC")
                .build();

        writer.write(new Chunk<>(List.of(candidate)));

        FlightTicketInventory saved = inventoryRepository
                .findByFlightCodeAndDepartureTimeAndSeatClass(flightCode, departureTime, SeatClass.ECONOMY)
                .orElseThrow();
        createdIds = List.of(saved.getId());

        assertEquals("SYNC", saved.getSourceSystem());
        assertEquals(InventoryStatus.OPEN, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getLastSyncedAt());
        assertEquals(0, new BigDecimal("1000000").compareTo(saved.getPrice()));
    }

    @Test
    void write_updateKhiDaCoDungUniqueKey_khongDongVaoStatusVaTotalSeats() throws Exception {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(3).withNano(0);
        String flightCode = "SYNC-" + System.nanoTime();

        FlightTicketInventory existing = inventoryRepository.save(FlightTicketInventory.builder()
                .flightCode(flightCode)
                .airline("Test Airline")
                .origin("HAN")
                .destination("SGN")
                .departureTime(departureTime)
                .arrivalTime(departureTime.plusHours(2))
                .seatClass(SeatClass.ECONOMY)
                .price(new BigDecimal("1000000"))
                .totalSeats(180) // admin đã set công suất thật (Task 4)
                .availableSeats(50)
                .status(InventoryStatus.CLOSED) // admin đã tay đóng vé
                .sourceSystem("MANUAL")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());
        createdIds = List.of(existing.getId());

        FlightTicketInventory candidate = FlightTicketInventory.builder()
                .flightCode(flightCode)
                .airline("Test Airline")
                .origin("HAN")
                .destination("SGN")
                .departureTime(departureTime)
                .arrivalTime(departureTime.plusHours(2))
                .seatClass(SeatClass.ECONOMY)
                .price(new BigDecimal("1234000"))
                .totalSeats(60) // giá trị "rác" từ mock - Writer KHÔNG được dùng khi update
                .availableSeats(45)
                .status(InventoryStatus.OPEN) // giá trị "rác" từ mock - Writer KHÔNG được dùng khi update
                .sourceSystem("SYNC")
                .build();

        writer.write(new Chunk<>(List.of(candidate)));

        FlightTicketInventory updated = inventoryRepository.findById(existing.getId()).orElseThrow();

        assertEquals(0, new BigDecimal("1234000").compareTo(updated.getPrice()));
        assertEquals(45, updated.getAvailableSeats());
        assertEquals(180, updated.getTotalSeats()); // giữ nguyên - KHÔNG bị ghi đè
        assertEquals(InventoryStatus.CLOSED, updated.getStatus()); // giữ nguyên - KHÔNG tự mở lại
        assertEquals("MANUAL", updated.getSourceSystem()); // giữ nguyên
    }
}
