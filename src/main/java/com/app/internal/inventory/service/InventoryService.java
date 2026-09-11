package com.app.internal.inventory.service;

import com.app.internal.common.exception.DuplicateInventoryException;
import com.app.internal.common.exception.InvalidSeatCountException;
import com.app.internal.common.exception.InventoryNotAvailableException;
import com.app.internal.common.exception.InventoryNotFoundException;
import com.app.internal.common.exception.SeatUnavailableException;
import com.app.internal.inventory.dto.InventoryCreateRequest;
import com.app.internal.inventory.dto.InventoryResponse;
import com.app.internal.inventory.dto.InventoryUpdateRequest;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.repository.InventoryRepository;
import com.app.internal.search.cache.SearchCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
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
    private final SearchCacheService searchCacheService;

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

        FlightTicketInventory saved = inventoryRepository.save(inventory);
        scheduleInvalidation(saved);
        return toResponse(saved);
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

        // Giá vừa đổi -> cache search route này đang giữ giá cũ, phải xoá
        // ngay (xem checklist TASK6_SEARCH_REDIS_CACHE.md: "sửa giá -> search
        // lại thấy giá mới ngay, không đợi hết TTL").
        scheduleInvalidation(inventory);
        return toResponse(inventory);
    }

    @Transactional
    public InventoryResponse changeStatus(Long id, InventoryStatus newStatus) {
        FlightTicketInventory inventory = findOrThrow(id);
        inventory.setStatus(newStatus);
        scheduleInvalidation(inventory);
        return toResponse(inventory);
    }

    // "Xoá" = đóng vé, KHÔNG DELETE row thật - Task 7 sau này Booking sẽ tham
    // chiếu FK tới đúng row này.
    @Transactional
    public void closeInventory(Long id) {
        FlightTicketInventory inventory = findOrThrow(id);
        inventory.setStatus(InventoryStatus.CANCELLED);
        scheduleInvalidation(inventory);
    }

    // Task 7 (BookingService.reserve): trừ ghế lúc giữ chỗ. saveAndFlush() ép
    // Hibernate bắn UPDATE ... WHERE id=? AND version=? NGAY LẬP TỨC (không
    // đợi tới cuối transaction) để bắt OptimisticLockingFailureException ngay
    // tại đây, trong cùng transaction với việc tạo Booking (đúng yêu cầu "trừ
    // ghế + tạo booking phải nằm trong 1 @Transactional" - GIAO_AN Task 7).
    // 2 request cùng trừ ghế cuối cùng: request thua sẽ có UPDATE khớp 0 dòng
    // (version đã bị request thắng bump trước đó) -> Hibernate tự phát hiện,
    // KHÔNG cần tự tay lock tay (SELECT ... FOR UPDATE).
    @Transactional
    public FlightTicketInventory decrementSeats(Long inventoryId, int passengerCount) {
        FlightTicketInventory inventory = findOrThrow(inventoryId);

        if (inventory.getStatus() != InventoryStatus.OPEN) {
            throw new InventoryNotAvailableException(
                    "Vé id=" + inventoryId + " không còn mở bán (status=" + inventory.getStatus() + ")");
        }
        if (inventory.getAvailableSeats() < passengerCount) {
            throw new SeatUnavailableException(
                    "Không đủ ghế trống (còn " + inventory.getAvailableSeats() + ", cần " + passengerCount + ")");
        }

        inventory.setAvailableSeats(inventory.getAvailableSeats() - passengerCount);
        try {
            inventoryRepository.saveAndFlush(inventory);
        } catch (OptimisticLockingFailureException e) {
            throw new SeatUnavailableException("Vé vừa hết chỗ do người khác đặt trước, vui lòng thử lại");
        }

        scheduleInvalidation(inventory);
        return inventory;
    }

    // Task 7: hoàn ghế khi booking bị huỷ/hết hạn/thanh toán thất bại. KHÔNG
    // check status OPEN/CLOSED - hoàn ghế phải luôn thành công bất kể vé đã
    // bị admin đóng sau khi booking được tạo hay chưa (số liệu availableSeats
    // vẫn phải đúng, độc lập với status hiển thị).
    @Transactional
    public void incrementSeats(Long inventoryId, int passengerCount) {
        FlightTicketInventory inventory = findOrThrow(inventoryId);
        inventory.setAvailableSeats(inventory.getAvailableSeats() + passengerCount);
        inventoryRepository.saveAndFlush(inventory);
        scheduleInvalidation(inventory);
    }

    private void scheduleInvalidation(FlightTicketInventory inventory) {
        searchCacheService.evictAfterCommit(inventory);
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
