package com.app.internal.sync.staging.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Bảng trung gian giữa mock API bên thứ 3 và FlightTicketInventory thật -
// cột tiền tố "raw" để nhấn mạnh đây là dữ liệu CHƯA validate/chuẩn hoá,
// không được dùng trực tiếp (xem sync/batch/processor ở Bước 8). Giữ tách
// biệt để 1 dòng lỗi không làm hỏng Inventory đang chạy tốt, đồng thời giữ
// lại lịch sử fetch để debug "lúc đó API bên thứ 3 trả cái gì".
@Entity
@Table(
        name = "flight_staging_record",
        indexes = @Index(
                name = "idx_staging_batch_processed",
                columnList = "batch_id, processed"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightStagingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ID của 1 lần Consumer#1 fetch (1 UUID mới mỗi lần trigger) - dùng để
    // Reader (Bước 8) đọc đúng nhóm dữ liệu của 1 lần sync, không lẫn giữa
    // các lần chạy khác nhau.
    @Column(name = "batch_id", nullable = false, length = 36)
    private String batchId;

    @Column(name = "raw_flight_code", nullable = false, length = 20)
    private String rawFlightCode;

    @Column(name = "raw_airline", nullable = false, length = 100)
    private String rawAirline;

    @Column(name = "raw_origin", nullable = false, length = 10)
    private String rawOrigin;

    @Column(name = "raw_destination", nullable = false, length = 10)
    private String rawDestination;

    @Column(name = "raw_departure_time")
    private LocalDateTime rawDepartureTime;

    @Column(name = "raw_arrival_time")
    private LocalDateTime rawArrivalTime;

    // String, không phải enum SeatClass - dữ liệu thô từ ngoài có thể chứa
    // giá trị không hợp lệ, việc validate/map sang enum thật là việc của
    // Processor (Bước 8), không phải của tầng lưu trữ thô này.
    @Column(name = "raw_seat_class", length = 20)
    private String rawSeatClass;

    @Column(name = "raw_price", precision = 12, scale = 2)
    private BigDecimal rawPrice;

    @Column(name = "raw_seats_left")
    private Integer rawSeatsLeft;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    // false khi vừa fetch xong, Writer (Bước 9) đánh dấu true sau khi đã
    // upsert thành công vào FlightTicketInventory - Reader chỉ đọc dòng
    // processed=false để tránh xử lý lại dữ liệu đã import.
    @Column(name = "processed", nullable = false)
    @Builder.Default
    private boolean processed = false;
}
