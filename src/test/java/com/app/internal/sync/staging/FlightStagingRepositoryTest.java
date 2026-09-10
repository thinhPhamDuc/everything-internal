package com.app.internal.sync.staging;

import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Kiểm chứng Reader (Bước 8) sẽ đọc đúng nhóm dữ liệu của 1 lần sync: chỉ
// lấy dòng cùng batchId và chưa processed, không lẫn batch khác hay dòng đã
// import rồi (đúng ý đồ "processed" đóng vai trò cursor thay vì last-id).
@SpringBootTest
class FlightStagingRepositoryTest {

    @Autowired
    private FlightStagingRepository stagingRepository;

    private List<Long> createdIds;

    @AfterEach
    void tearDown() {
        if (createdIds != null) {
            stagingRepository.deleteAllByIdInBatch(createdIds);
        }
    }

    private FlightStagingRecord buildRecord(String batchId, String flightCode, boolean processed) {
        return FlightStagingRecord.builder()
                .batchId(batchId)
                .rawFlightCode(flightCode)
                .rawAirline("Test Airline")
                .rawOrigin("HAN")
                .rawDestination("SGN")
                .rawDepartureTime(LocalDateTime.now().plusDays(1))
                .rawArrivalTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .rawSeatClass("ECONOMY")
                .rawPrice(new BigDecimal("1000000"))
                .rawSeatsLeft(50)
                .fetchedAt(LocalDateTime.now())
                .processed(processed)
                .build();
    }

    @Test
    void findByBatchIdAndProcessedFalse_chiTraDungNhomBatchIdChuaProcessed() {
        String batchA = "batch-A-" + System.nanoTime();
        String batchB = "batch-B-" + System.nanoTime();

        FlightStagingRecord a1 = stagingRepository.save(buildRecord(batchA, "VN101", false));
        FlightStagingRecord a2 = stagingRepository.save(buildRecord(batchA, "VN102", false));
        FlightStagingRecord aProcessed = stagingRepository.save(buildRecord(batchA, "VN103", true));
        FlightStagingRecord b1 = stagingRepository.save(buildRecord(batchB, "VJ201", false));

        createdIds = List.of(a1.getId(), a2.getId(), aProcessed.getId(), b1.getId());

        List<FlightStagingRecord> result = stagingRepository.findByBatchIdAndProcessedFalse(batchA);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(r -> r.getBatchId().equals(batchA)));
        assertTrue(result.stream().noneMatch(FlightStagingRecord::isProcessed));
        assertTrue(result.stream().anyMatch(r -> r.getRawFlightCode().equals("VN101")));
        assertTrue(result.stream().anyMatch(r -> r.getRawFlightCode().equals("VN102")));
    }
}
