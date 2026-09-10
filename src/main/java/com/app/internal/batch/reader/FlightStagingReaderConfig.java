package com.app.internal.batch.reader;

import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Map;

@Configuration
public class FlightStagingReaderConfig {

    // @StepScope BẮT BUỘC: thiếu nó, Spring tạo bean này 1 LẦN DUY NHẤT lúc
    // context khởi động - lúc đó jobParameters['batchId'] chưa tồn tại
    // (batchId chỉ có khi JobLauncher.run() được gọi ở Bước 10) -> lỗi hoặc
    // luôn đọc batchId rỗng/của lần chạy đầu tiên cho mọi lần chạy sau.
    @Bean
    @StepScope
    public RepositoryItemReader<FlightStagingRecord> flightStagingReader(
            FlightStagingRepository repository,
            @Value("#{jobParameters['batchId']}") String batchId) {

        // RepositoryItemReader phân trang bằng repository thật -> BẮT BUỘC
        // có sort ổn định (theo id) để không đọc lặp/bỏ sót dòng giữa các
        // trang khi phân trang.
        RepositoryItemReader<FlightStagingRecord> reader =
                new RepositoryItemReader<>(repository, Map.of("id", Sort.Direction.ASC));
        reader.setMethodName("findByBatchIdAndProcessedFalse");
        reader.setArguments(List.of(batchId));
        return reader;
    }
}
