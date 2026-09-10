package com.app.internal.sync.mq;

import com.app.internal.sync.dto.BatchReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Component;

// Consumer#2: nhận batchId (chặng 2) -> chạy Spring Batch Job. Đây là mắt
// xích cuối nối staging (Bước 1-6) với Inventory thật (Bước 7-9).
// JobOperator thay cho JobLauncher (Spring Batch 6 deprecate JobLauncher.run() -
// JobOperator kế thừa JobLauncher, dùng start() không bị deprecated).
@Slf4j
@Component
@RequiredArgsConstructor
public class FlightBatchTriggerListener {

    private final JobOperator jobOperator;
    private final Job flightImportJob;

    @RabbitListener(queues = SyncRabbitMQConfig.QUEUE_BATCH)
    public void handleBatchTrigger(BatchReadyEvent event) throws Exception {
        log.info("[Sync] Consumer#2 nhận batchId={}", event.batchId());

        // Thêm "timestamp" bên cạnh "batchId": mỗi lần gọi run() luôn tạo 1
        // JobInstance MỚI (đổi lấy khả năng chạy lại đúng batchId cũ khi
        // cần retry) - đánh đổi mất tính năng "tự chặn JobInstance trùng"
        // có sẵn của Spring Batch, bù lại bằng Writer upsert idempotent
        // theo unique key (Bước 9) nên chạy lại vẫn an toàn, không tạo
        // dòng Inventory trùng.
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("batchId", event.batchId())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobOperator.start(flightImportJob, jobParameters);
        log.info("[Sync] Consumer#2 Job kết thúc status={} batchId={}", execution.getStatus(), event.batchId());
    }
}
