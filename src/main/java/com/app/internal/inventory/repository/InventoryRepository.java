package com.app.internal.inventory.repository;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.SeatClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<FlightTicketInventory, Long> {

    Optional<FlightTicketInventory> findByFlightCodeAndDepartureTimeAndSeatClass(
            String flightCode, LocalDateTime departureTime, SeatClass seatClass);

    // Containing + IgnoreCase với default "" ở Controller -> "" là substring
    // của mọi chuỗi nên không lọc gì khi bỏ trống param, không cần nhánh
    // if/else riêng cho "có lọc hay không" ở Service.
    Page<FlightTicketInventory> findByOriginContainingIgnoreCaseAndDestinationContainingIgnoreCase(
            String origin, String destination, Pageable pageable);
}
