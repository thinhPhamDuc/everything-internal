package com.app.internal.inventory.entity;

import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Task 4: bảng trung tâm của cả hệ thống - Task 5 (sync tự động) sẽ upsert
// vào đúng bảng này theo unique key (flightCode + departureTime + seatClass);
// Task 6 (search) sẽ đọc theo index (origin, destination, departureTime).
@Entity
@Table(
        name = "flight_ticket_inventory",
        // Task 9: thêm "provider" vào unique key - quyết định đã chốt là GIỮ
        // RIÊNG từng dòng theo provider (không merge lấy giá rẻ nhất), để
        // khách thấy được nhiều lựa chọn cùng chặng/giờ từ các nguồn khác
        // nhau (giống OTA thật hiển thị so sánh giá). Vé nhập tay (Task 4)
        // dùng provider="MANUAL" (xem InventoryService) - KHÔNG để null vì
        // MySQL coi NULL khác NULL trong unique constraint, sẽ vô tình cho
        // phép trùng vé nhập tay.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inventory_flight_departure_class_provider",
                columnNames = {"flight_code", "departure_time", "seat_class", "provider"}
        ),
        indexes = @Index(
                name = "idx_inventory_route_departure",
                columnList = "origin, destination, departure_time"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightTicketInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_code", nullable = false, length = 20)
    private String flightCode;

    @Column(name = "airline", nullable = false, length = 100)
    private String airline;

    // Mã sân bay dạng IATA (VD "HAN", "SGN") - so khớp chính xác, không phụ
    // thuộc chính tả tên đầy đủ.
    @Column(name = "origin", nullable = false, length = 10)
    private String origin;

    @Column(name = "destination", nullable = false, length = 10)
    private String destination;

    @Column(name = "departure_time", nullable = false)
    private LocalDateTime departureTime;

    @Column(name = "arrival_time", nullable = false)
    private LocalDateTime arrivalTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_class", nullable = false, length = 20)
    private SeatClass seatClass;

    // BigDecimal - KHÔNG dùng double/float cho tiền tệ (lỗi làm tròn số học
    // dấu phẩy động, VD 0.1 + 0.2 != 0.3), quan trọng khi có Task 7 thanh toán.
    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats;

    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryStatus status;

    // "MANUAL" khi admin tự nhập qua API này, "SYNC" khi Task 5 đổ dữ liệu
    // vào tự động - chưa dùng để phân nhánh logic ở Task 4, khai báo sẵn.
    @Column(name = "source_system", nullable = false, length = 20)
    private String sourceSystem;

    // Task 9: tên nhà cung cấp cụ thể (VD "PROVIDER_A") khi sourceSystem=SYNC,
    // hoặc "MANUAL" khi admin tự nhập (xem InventoryService.SOURCE_MANUAL) -
    // khác sourceSystem ở chỗ sourceSystem chỉ phân biệt "tự động hay tay",
    // còn provider phân biệt CỤ THỂ nguồn nào trong nhiều nguồn tự động.
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Optimistic locking chuẩn bị sẵn cho Task 7 (nhiều request trừ
    // availableSeats cùng lúc) - Hibernate tự tăng version mỗi lần update,
    // không set tay, không cần dùng ngay ở Task 4.
    @Version
    @Column(name = "version")
    private Long version;
}
