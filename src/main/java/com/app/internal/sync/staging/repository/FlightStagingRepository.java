package com.app.internal.sync.staging.repository;

import com.app.internal.sync.staging.entity.FlightStagingRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlightStagingRepository extends JpaRepository<FlightStagingRecord, Long> {

    // Dùng cho query đơn giản (VD test, service không cần phân trang).
    List<FlightStagingRecord> findByBatchIdAndProcessedFalse(String batchId);

    // Overload riêng cho RepositoryItemReader (Bước 8): reader gọi method
    // qua reflection và TỰ ĐỘNG thêm 1 tham số Pageable vào cuối lời gọi để
    // tự phân trang qua staging - thiếu overload này, reader ném
    // NoSuchMethodException vì không tìm thấy đúng chữ ký (String, Pageable).
    // Kiểu trả về PHẢI là Slice/Page (không phải List thường) - reader tự
    // cast kết quả sang Slice để biết "còn trang tiếp theo không", List
    // thường gây ClassCastException lúc runtime.
    Slice<FlightStagingRecord> findByBatchIdAndProcessedFalse(String batchId, Pageable pageable);
}
