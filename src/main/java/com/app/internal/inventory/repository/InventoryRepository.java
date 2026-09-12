package com.app.internal.inventory.repository;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.SeatClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<FlightTicketInventory, Long> {

    Optional<FlightTicketInventory> findByFlightCodeAndDepartureTimeAndSeatClass(
            String flightCode, LocalDateTime departureTime, SeatClass seatClass);

    // Containing + IgnoreCase với default "" ở Controller -> "" là substring
    // của mọi chuỗi nên không lọc gì khi bỏ trống param, không cần nhánh
    // if/else riêng cho "có lọc hay không" ở Service.
    Page<FlightTicketInventory> findByOriginContainingIgnoreCaseAndDestinationContainingIgnoreCase(
            String origin, String destination, Pageable pageable);

    // Task 6: search cho client - startOfDay/endOfDay tính từ departureDate ở
    // Service (JPQL không so sánh trực tiếp LocalDateTime với LocalDate).
    // Sort theo giá tăng dần ngay trong query.
    //
    // CỐ Ý KHÔNG lọc availableSeats >= passengerCount ở đây (khác Phase A) -
    // Phase B (TASK6_SEARCH_REDIS_CACHE.md) cache nguyên list khớp
    // route+ngày+hạng ghế, lọc passengerCount áp dụng SAU khi lấy dữ liệu
    // (cache hit hay MySQL đều qua chung 1 bước lọc ở FlightSearchService),
    // để 1 route+ngày hot chỉ tốn đúng 1 cache entry thay vì nổ theo mọi tổ
    // hợp passengerCount.
    @Query("""
            SELECT i FROM FlightTicketInventory i
            WHERE i.origin = :origin
              AND i.destination = :destination
              AND i.departureTime >= :startOfDay AND i.departureTime < :endOfDay
              AND i.seatClass = :seatClass
              AND i.status = com.app.internal.inventory.enums.InventoryStatus.OPEN
            ORDER BY i.price ASC
            """)
    List<FlightTicketInventory> searchAvailable(
            @Param("origin") String origin,
            @Param("destination") String destination,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay,
            @Param("seatClass") SeatClass seatClass);
}
