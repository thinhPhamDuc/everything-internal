package com.app.internal.batch.reader;

import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// @DataJpaTest (thay vì @SpringBootTest) CỐ Ý - context đầy đủ của app
// chưa boot được lúc này (FlightImportJobConfig còn thiếu ItemWriter, Bước
// 9), @DataJpaTest chỉ nạp JPA/repository, không đụng tới batch Job config.
// replace=NONE để dùng đúng Postgres thật (project không có H2).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FlightStagingReaderConfigTest {

    @Autowired
    private FlightStagingRepository stagingRepository;

    private List<Long> createdIds;

    @AfterEach
    void tearDown() {
        if (createdIds != null) {
            stagingRepository.deleteAllByIdInBatch(createdIds);
        }
    }

    @Test
    void reader_docDuocDungBatchIdQuaRepositoryItemReader() throws Exception {
        String batchId = "reader-test-" + System.nanoTime();

        FlightStagingRecord r1 = stagingRepository.save(buildRecord(batchId, "VN101"));
        FlightStagingRecord r2 = stagingRepository.save(buildRecord(batchId, "VN102"));
        createdIds = List.of(r1.getId(), r2.getId());

        RepositoryItemReader<FlightStagingRecord> reader =
                new RepositoryItemReader<>(stagingRepository, Map.of("id", Sort.Direction.ASC));
        reader.setMethodName("findByBatchIdAndProcessedFalse");
        reader.setArguments(List.of(batchId));
        reader.setPageSize(10);

        reader.open(new ExecutionContext());
        try {
            FlightStagingRecord first = reader.read();
            FlightStagingRecord second = reader.read();
            FlightStagingRecord third = reader.read();

            assertNotNull(first);
            assertNotNull(second);
            assertNull(third); // hết dữ liệu - đúng giao thức ItemReader: null báo "hết item"
        } finally {
            reader.close();
        }
    }

    private FlightStagingRecord buildRecord(String batchId, String flightCode) {
        return FlightStagingRecord.builder()
                .batchId(batchId)
                .provider("PROVIDER_A")
                .rawFlightCode(flightCode)
                .rawAirline("Test Airline")
                .rawOrigin("HAN")
                .rawDestination("SGN")
                .rawDepartureTime(LocalDateTime.now().plusDays(1))
                .rawArrivalTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .rawSeatClass("ECONOMY")
                .rawPrice(new BigDecimal("1000000"))
                .rawSeatsLeft(10)
                .fetchedAt(LocalDateTime.now())
                .processed(false)
                .build();
    }
}
