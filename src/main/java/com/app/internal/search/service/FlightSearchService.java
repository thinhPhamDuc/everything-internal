package com.app.internal.search.service;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.repository.InventoryRepository;
import com.app.internal.search.cache.SearchCacheService;
import com.app.internal.search.dto.FlightSearchRequest;
import com.app.internal.search.dto.FlightSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// Tách rõ loadMatches()/formatResponse() (đúng TASK6_SEARCH_REDIS_CACHE.md)
// để Phase B chèn Redis cache vào giữa loadMatches() mà không đụng
// search()/formatResponse().
@Service
@RequiredArgsConstructor
public class FlightSearchService {

    private final InventoryRepository inventoryRepository;
    private final SearchCacheService searchCacheService;

    @Transactional(readOnly = true)
    public List<FlightSearchResponse> search(FlightSearchRequest request) {
        List<FlightTicketInventory> matches = loadMatches(request);
        // passengerCount lọc SAU khi lấy matches (cache hit hay MySQL đều
        // qua đây) - matches chỉ khớp route+ngày+hạng ghế, KHÔNG khớp sẵn
        // passengerCount (xem InventoryRepository.searchAvailable).
        List<FlightTicketInventory> withEnoughSeats = matches.stream()
                .filter(m -> m.getAvailableSeats() >= request.passengerCount())
                .toList();
        return formatResponse(withEnoughSeats);
    }

    // Phase B: cache-aside - đọc cache trước, miss thì query MySQL rồi
    // ghi lại cache (best-effort, không chặn response nếu Redis lỗi - xem
    // SearchCacheService).
    private List<FlightTicketInventory> loadMatches(FlightSearchRequest request) {
        String key = searchCacheService.buildKey(
                request.origin(), request.destination(), request.departureDate(), request.seatClass());

        List<FlightTicketInventory> cached = searchCacheService.get(key);
        if (cached != null) {
            return cached;
        }

        LocalDateTime startOfDay = request.departureDate().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);

        List<FlightTicketInventory> matches = inventoryRepository.searchAvailable(
                request.origin(),
                request.destination(),
                startOfDay,
                endOfDay,
                request.seatClass());

        searchCacheService.put(key, matches, request.departureDate());
        return matches;
    }

    private List<FlightSearchResponse> formatResponse(List<FlightTicketInventory> matches) {
        return matches.stream().map(this::toResponse).toList();
    }

    private FlightSearchResponse toResponse(FlightTicketInventory inventory) {
        return new FlightSearchResponse(
                inventory.getId(),
                inventory.getFlightCode(),
                inventory.getAirline(),
                inventory.getOrigin(),
                inventory.getDestination(),
                inventory.getDepartureTime(),
                inventory.getArrivalTime(),
                inventory.getSeatClass().name(),
                inventory.getPrice(),
                inventory.getAvailableSeats(),
                inventory.getProvider());
    }
}
