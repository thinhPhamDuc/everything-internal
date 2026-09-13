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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                .provider("PROVIDER_A")
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
                .findByFlightCodeAndDepartureTimeAndSeatClassAndProvider(
                        flightCode, departureTime, SeatClass.ECONOMY, "PROVIDER_A")
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

        // existing mô phỏng 1 dòng ĐÃ TỪNG được PROVIDER_A sync vào ở chu kỳ
        // trước, sau đó admin tay đóng vé (Task 4 changeStatus) + tự chỉnh
        // totalSeats - Task 9: unique key giờ gồm cả provider, nên candidate
        // dưới đây PHẢI cùng provider="PROVIDER_A" mới khớp đúng dòng này (1
        // candidate provider KHÁC, VD "PROVIDER_B", sẽ tạo dòng MỚI riêng
        // thay vì update - đúng ý đồ "giữ riêng theo provider" của Task 9).
        FlightTicketInventory existing = inventoryRepository.save(FlightTicketInventory.builder()
                .provider("PROVIDER_A")
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
                .sourceSystem("SYNC")
                .createdAt(LocalDateTime.now().minusDays(1))
                .build());
        createdIds = List.of(existing.getId());

        FlightTicketInventory candidate = FlightTicketInventory.builder()
                .provider("PROVIDER_A")
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
        assertEquals("PROVIDER_A", updated.getProvider()); // giữ nguyên - vẫn đúng 1 dòng cũ, không tạo mới
    }

    // Task 9: quyết định đã chốt "giữ riêng từng dòng theo provider" (không
    // merge lấy giá rẻ nhất) - 2 provider cùng flightCode+departureTime+
    // seatClass PHẢI tạo ra 2 dòng Inventory riêng, không được ghi đè lẫn
    // nhau, để khách thấy được nhiều lựa chọn cùng chặng/giờ từ các nguồn
    // khác nhau.
    @Test
    void write_2ProviderCungFlightCodeDepartureSeatClass_taoRa2DongRieng() throws Exception {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(3).withNano(0);
        String flightCode = "SYNC-" + System.nanoTime();

        FlightTicketInventory fromProviderA = FlightTicketInventory.builder()
                .provider("PROVIDER_A")
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
        FlightTicketInventory fromProviderB = FlightTicketInventory.builder()
                .provider("PROVIDER_B")
                .flightCode(flightCode)
                .airline("Test Airline")
                .origin("HAN")
                .destination("SGN")
                .departureTime(departureTime)
                .arrivalTime(departureTime.plusHours(2))
                .seatClass(SeatClass.ECONOMY)
                .price(new BigDecimal("1100000")) // giá khác PROVIDER_A - đúng ý "so sánh giá"
                .totalSeats(30)
                .availableSeats(30)
                .status(InventoryStatus.OPEN)
                .sourceSystem("SYNC")
                .build();

        writer.write(new Chunk<>(List.of(fromProviderA, fromProviderB)));

        FlightTicketInventory savedA = inventoryRepository
                .findByFlightCodeAndDepartureTimeAndSeatClassAndProvider(
                        flightCode, departureTime, SeatClass.ECONOMY, "PROVIDER_A")
                .orElseThrow();
        FlightTicketInventory savedB = inventoryRepository
                .findByFlightCodeAndDepartureTimeAndSeatClassAndProvider(
                        flightCode, departureTime, SeatClass.ECONOMY, "PROVIDER_B")
                .orElseThrow();
        createdIds = List.of(savedA.getId(), savedB.getId());

        assertNotEquals(savedA.getId(), savedB.getId());
        assertEquals(0, new BigDecimal("1000000").compareTo(savedA.getPrice()));
        assertEquals(0, new BigDecimal("1100000").compareTo(savedB.getPrice()));
    }
}
