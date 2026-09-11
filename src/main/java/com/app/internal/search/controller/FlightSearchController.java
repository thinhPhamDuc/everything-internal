package com.app.internal.search.controller;

import com.app.internal.search.dto.FlightSearchRequest;
import com.app.internal.search.dto.FlightSearchResponse;
import com.app.internal.search.service.FlightSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Public, KHÔNG @PreAuthorize - client chưa đăng nhập vẫn tìm được vé
// (xem SecurityConfig - /flights/search được permitAll cùng /auth/**).
@RestController
@RequestMapping("/flights")
@RequiredArgsConstructor
public class FlightSearchController {

    private final FlightSearchService flightSearchService;

    @GetMapping("/search")
    public ResponseEntity<List<FlightSearchResponse>> search(@Valid @ModelAttribute FlightSearchRequest request) {
        return ResponseEntity.ok(flightSearchService.search(request));
    }
}
