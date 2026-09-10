package com.app.internal.inventory;

import com.app.internal.common.exception.DuplicateInventoryException;
import com.app.internal.common.exception.InvalidSeatCountException;
import com.app.internal.common.exception.InventoryNotFoundException;
import com.app.internal.inventory.dto.InventoryCreateRequest;
import com.app.internal.inventory.dto.InventoryResponse;
import com.app.internal.inventory.dto.InventoryUpdateRequest;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.inventory.repository.InventoryRepository;
import com.app.internal.inventory.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

// Kiểm chứng đúng các quyết định thiết kế ở TASK4_CRUD_INVENTORY.md: tạo mới
// tự set availableSeats = totalSeats, chặn trùng unique key (flightCode +
// departureTime + seatClass), chặn giảm totalSeats xuống dưới availableSeats,
// và "xoá" chỉ đổi status CANCELLED chứ không DELETE row thật.
@SpringBootTest
class InventoryServiceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Long inventoryId;

    @AfterEach
    void tearDown() {
        if (inventoryId != null) {
            inventoryRepository.findById(inventoryId).ifPresent(inventoryRepository::delete);
        }
    }

    private InventoryCreateRequest buildCreateRequest(String flightCode, LocalDateTime departureTime) {
        InventoryCreateRequest request = new InventoryCreateRequest();
        request.setFlightCode(flightCode);
        request.setAirline("Test Airline");
        request.setOrigin("HAN");
        request.setDestination("SGN");
        request.setDepartureTime(departureTime);
        request.setArrivalTime(departureTime.plusHours(2));
        request.setSeatClass(SeatClass.ECONOMY);
        request.setPrice(new BigDecimal("1500000"));
        request.setTotalSeats(100);
        return request;
    }

    private String shortUniqueFlightCode(String prefix) {
        return prefix + (System.nanoTime() % 100000);
    }

    @Test
    void createInventory_thanhCong_availableSeatsBangTotalSeats() {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(10).withNano(0);
        InventoryCreateRequest request = buildCreateRequest(shortUniqueFlightCode("VT"), departureTime);

        InventoryResponse response = inventoryService.createInventory(request);
        inventoryId = response.id();

        assertEquals(request.getTotalSeats(), response.availableSeats());
        assertEquals(InventoryStatus.OPEN.name(), response.status());
        assertEquals("MANUAL", response.sourceSystem());
    }

    @Test
    void createInventory_trungFlightCodeDepartureTimeSeatClass_nemDuplicateInventoryException() {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(11).withNano(0);
        String flightCode = shortUniqueFlightCode("VD");
        InventoryCreateRequest first = buildCreateRequest(flightCode, departureTime);
        inventoryId = inventoryService.createInventory(first).id();

        InventoryCreateRequest second = buildCreateRequest(flightCode, departureTime);
        assertThrows(DuplicateInventoryException.class, () -> inventoryService.createInventory(second));
    }

    @Test
    void updateInventory_giamTotalSeatsDuoiAvailableSeats_nemInvalidSeatCountException() {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(12).withNano(0);
        InventoryCreateRequest createRequest = buildCreateRequest(shortUniqueFlightCode("VS"), departureTime);
        inventoryId = inventoryService.createInventory(createRequest).id();

        InventoryUpdateRequest updateRequest = new InventoryUpdateRequest();
        updateRequest.setAirline("Test Airline");
        updateRequest.setPrice(new BigDecimal("1600000"));
        updateRequest.setTotalSeats(50); // availableSeats hiện tại là 100

        assertThrows(InvalidSeatCountException.class,
                () -> inventoryService.updateInventory(inventoryId, updateRequest));
    }

    @Test
    void closeInventory_khongXoaRowThat_chiDoiStatusThanhCancelled() {
        LocalDateTime departureTime = LocalDateTime.now().plusDays(13).withNano(0);
        InventoryCreateRequest createRequest = buildCreateRequest(shortUniqueFlightCode("VC"), departureTime);
        inventoryId = inventoryService.createInventory(createRequest).id();

        inventoryService.closeInventory(inventoryId);

        InventoryResponse response = inventoryService.getInventoryById(inventoryId);
        assertEquals(InventoryStatus.CANCELLED.name(), response.status());
        assertTrue(inventoryRepository.findById(inventoryId).isPresent());
    }

    @Test
    void getInventoryById_khongTonTai_nemInventoryNotFoundException() {
        assertThrows(InventoryNotFoundException.class, () -> inventoryService.getInventoryById(-1L));
    }
}
