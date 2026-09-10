package com.app.internal.sync.mock.controller;

import com.app.internal.sync.mock.dto.MockFlightDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Giả lập API của 1 hãng/GDS bên thứ 3 - không thuộc hệ thống mình quản lý
// quyền nên public, không @PreAuthorize (xem SecurityConfig: permitAll cho
// "/mock/third-party/**"). Mỗi lần gọi trả dữ liệu dao động nhẹ (price,
// seatsLeft ±10%) để mô phỏng dữ liệu đối tác luôn "sống" - dùng để tự mắt
// xác nhận Inventory thay đổi qua mỗi lần Job Task 5 chạy, không phải do
// cache/trùng hợp.
@RestController
public class ThirdPartyFlightMockController {

    private static final List<FlightTemplate> TEMPLATES = List.of(
            new FlightTemplate("VN101", "Vietnam Airlines", "HAN", "SGN", 3, 8, 0, 2, "ECONOMY", new BigDecimal("1200000"), 42),
            new FlightTemplate("VN101", "Vietnam Airlines", "HAN", "SGN", 3, 8, 0, 2, "BUSINESS", new BigDecimal("3500000"), 12),
            new FlightTemplate("VJ202", "VietJet Air", "SGN", "DAD", 2, 10, 30, 1, "ECONOMY", new BigDecimal("850000"), 60),
            new FlightTemplate("QH303", "Bamboo Airways", "HAN", "DAD", 4, 14, 0, 1, "ECONOMY", new BigDecimal("990000"), 35),
            new FlightTemplate("VN404", "Vietnam Airlines", "SGN", "HAN", 5, 16, 30, 2, "ECONOMY", new BigDecimal("1250000"), 50),
            new FlightTemplate("VJ505", "VietJet Air", "DAD", "SGN", 1, 18, 0, 1, "ECONOMY", new BigDecimal("870000"), 28)
    );

    @GetMapping("/mock/third-party/flights")
    public List<MockFlightDto> getFlights() {
        LocalDateTime now = LocalDateTime.now();
        return TEMPLATES.stream()
                .map(template -> template.toDto(now))
                .toList();
    }

    private record FlightTemplate(
            String flightCode,
            String airline,
            String origin,
            String destination,
            int daysFromNow,
            int departureHour,
            int departureMinute,
            int durationHours,
            String seatClass,
            BigDecimal basePrice,
            int baseSeatsLeft
    ) {
        // departureHour/departureMinute CỐ ĐỊNH (không random) - unique key
        // upsert ở Writer (Bước 9) là (flightCode + departureTime +
        // seatClass), nên departureTime của "cùng 1 chuyến" phải ổn định
        // qua mỗi lần gọi, chỉ price/seatsLeft mới được dao động. Random cả
        // departureTime sẽ khiến mỗi lần sync tạo "chuyến mới" thay vì
        // update chuyến cũ - đúng bug đã bắt được khi test checklist Bước 11.
        MockFlightDto toDto(LocalDateTime now) {
            ThreadLocalRandom random = ThreadLocalRandom.current();

            LocalDateTime departureTime = now.plusDays(daysFromNow)
                    .withHour(departureHour)
                    .withMinute(departureMinute)
                    .withSecond(0)
                    .withNano(0);
            LocalDateTime arrivalTime = departureTime.plusHours(durationHours);

            double priceFactor = 0.9 + random.nextDouble() * 0.2; // dao động ±10%
            BigDecimal price = basePrice
                    .multiply(BigDecimal.valueOf(priceFactor))
                    .setScale(0, RoundingMode.HALF_UP);

            int seatsJitter = Math.max(1, Math.round(baseSeatsLeft * 0.1f));
            int seatsLeft = Math.max(0, baseSeatsLeft + random.nextInt(-seatsJitter, seatsJitter + 1));

            return new MockFlightDto(flightCode, airline, origin, destination,
                    departureTime, arrivalTime, seatClass, price, seatsLeft);
        }
    }
}
