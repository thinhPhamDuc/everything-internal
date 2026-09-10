package com.app.internal.inventory.service;

import com.app.internal.common.exception.DuplicateInventoryException;
import com.app.internal.common.exception.InvalidSeatCountException;
import com.app.internal.common.exception.InventoryNotFoundException;
import com.app.internal.inventory.dto.InventoryCreateRequest;
import com.app.internal.inventory.dto.InventoryResponse;
import com.app.internal.inventory.dto.InventoryUpdateRequest;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final String SOURCE_MANUAL = "MANUAL";

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public Page<InventoryResponse> listInventory(String origin, String destination, Pageable pageable) {
        return inventoryRepository
                .findByOriginContainingIgnoreCaseAndDestinationContainingIgnoreCase(origin, destination, pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventoryById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public InventoryResponse createInventory(InventoryCreateRequest request) {
        inventoryRepository
                .findByFlightCodeAndDepartureTimeAndSeatClass(
                        request.getFlightCode(), request.getDepartureTime(), request.getSeatClass())
                .ifPresent(existing -> {
                    throw new DuplicateInventoryException(
                            "Đã tồn tại vé với flightCode=" + request.getFlightCode()
                                    + ", departureTime=" + request.getDepartureTime()
                                    + ", seatClass=" + request.getSeatClass());
                });

        FlightTicketInventory inventory = FlightTicketInventory.builder()
                .flightCode(request.getFlightCode())
                .airline(request.getAirline())
                .origin(request.getOrigin())
                .destination(request.getDestination())
                .departureTime(request.getDepartureTime())
                .arrivalTime(request.getArrivalTime())
                .seatClass(request.getSeatClass())
                .price(request.getPrice())
                .totalSeats(request.getTotalSeats())
                // Vé vừa tạo chưa ai đặt -> availableSeats luôn bằng totalSeats,
                // không nhận trực tiếp từ request (xem InventoryCreateRequest).
                .availableSeats(request.getTotalSeats())
                .status(InventoryStatus.OPEN)
                .sourceSystem(SOURCE_MANUAL)
                .createdAt(LocalDateTime.now())
                .build();

        return toResponse(inventoryRepository.save(inventory));
    }

    @Transactional
    public InventoryResponse updateInventory(Long id, InventoryUpdateRequest request) {
        FlightTicketInventory inventory = findOrThrow(id);

        if (request.getTotalSeats() < inventory.getAvailableSeats()) {
            throw new InvalidSeatCountException(
                    "Không thể giảm totalSeats (" + request.getTotalSeats() + ") xuống dưới availableSeats hiện có ("
                            + inventory.getAvailableSeats() + ")");
        }

        inventory.setAirline(request.getAirline());
        inventory.setPrice(request.getPrice());
        inventory.setTotalSeats(request.getTotalSeats());

        return toResponse(inventory);
    }

    @Transactional
    public InventoryResponse changeStatus(Long id, InventoryStatus newStatus) {
        FlightTicketInventory inventory = findOrThrow(id);
        inventory.setStatus(newStatus);
        return toResponse(inventory);
    }

    // "Xoá" = đóng vé, KHÔNG DELETE row thật - Task 7 sau này Booking sẽ tham
    // chiếu FK tới đúng row này.
    @Transactional
    public void closeInventory(Long id) {
        FlightTicketInventory inventory = findOrThrow(id);
        inventory.setStatus(InventoryStatus.CANCELLED);
    }

    private FlightTicketInventory findOrThrow(Long id) {
        return inventoryRepository.findById(id)
                .orElseThrow(() -> new InventoryNotFoundException("Không tìm thấy inventory với id=" + id));
    }

    private InventoryResponse toResponse(FlightTicketInventory inventory) {
        return new InventoryResponse(
                inventory.getId(),
                inventory.getFlightCode(),
                inventory.getAirline(),
                inventory.getOrigin(),
                inventory.getDestination(),
                inventory.getDepartureTime(),
                inventory.getArrivalTime(),
                inventory.getSeatClass().name(),
                inventory.getPrice(),
                inventory.getTotalSeats(),
                inventory.getAvailableSeats(),
                inventory.getStatus().name(),
                inventory.getSourceSystem(),
                inventory.getLastSyncedAt());
    }
}
