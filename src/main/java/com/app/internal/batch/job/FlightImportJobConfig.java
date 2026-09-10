package com.app.internal.batch.job;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.sync.staging.entity.FlightStagingRecord;
import com.app.internal.sync.staging.repository.FlightStagingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

// Reader/Processor/Writer bean được inject ở đây do Bước 8-9 khai báo -
// context sẽ KHÔNG start được cho tới khi cả 3 bean đó tồn tại (bình
// thường, đúng thứ tự phụ thuộc của GIAO_AN.md).
@Configuration
@RequiredArgsConstructor
public class FlightImportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    // 2 Step nối tiếp: flightImportStep chạy xong COMPLETED (nghĩa là TOÀN
    // BỘ chunk đã commit vào Inventory) rồi mới tới markStagingProcessedStep
    // - thứ tự này đảm bảo không bao giờ đánh dấu processed=true trước khi
    // chắc chắn dữ liệu đã nằm trong Inventory.
    @Bean
    public Job flightImportJob(Step flightImportStep, Step markStagingProcessedStep) {
        return new JobBuilder("flightImportJob", jobRepository)
                .start(flightImportStep)
                .next(markStagingProcessedStep)
                .build();
    }

    @Bean
    public Step flightImportStep(
            ItemReader<FlightStagingRecord> reader,
            ItemProcessor<FlightStagingRecord, FlightTicketInventory> processor,
            ItemWriter<FlightTicketInventory> writer) {
        return new StepBuilder("flightImportStep", jobRepository)
                .<FlightStagingRecord, FlightTicketInventory>chunk(100) // 100 dòng/lần, đúng gợi ý GIAO_AN
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public Step markStagingProcessedStep(Tasklet markStagingProcessedTasklet) {
        return new StepBuilder("markStagingProcessedStep", jobRepository)
                .tasklet(markStagingProcessedTasklet, transactionManager)
                .build();
    }

    // @StepScope vì cùng lý do Reader ở Bước 8: cần đọc đúng batchId của lần
    // chạy Job này, chỉ có khi Step thật sự bắt đầu.
    @Bean
    @StepScope
    public Tasklet markStagingProcessedTasklet(
            FlightStagingRepository stagingRepository,
            @Value("#{jobParameters['batchId']}") String batchId) {
        return (contribution, chunkContext) -> {
            List<FlightStagingRecord> remaining = stagingRepository.findByBatchIdAndProcessedFalse(batchId);
            remaining.forEach(record -> record.setProcessed(true));
            stagingRepository.saveAll(remaining);
            return RepeatStatus.FINISHED;
        };
    }
}
