package com.app.internal.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;

// Spring Boot 4.1.1 / Spring Batch 6 mặc định dùng ResourcelessJobRepository
// (in-memory, KHÔNG tạo bảng BATCH_JOB_EXECUTION..., KHÔNG track lịch sử
// JobInstance qua các lần chạy khác nhau) - khác hẳn giả định của
// TASK5_SYNC_SCHEDULER_BATCH.md ("Spring Boot tự tạo bảng này"). Kế thừa
// JdbcDefaultBatchConfiguration để có JobRepository backed bởi MySQL thật,
// đúng ý đồ "tự kiểm tra bằng BATCH_JOB_EXECUTION" và "Spring Batch
// tự chặn chạy trùng JobInstance" của giáo án.
@Configuration
public class BatchJdbcConfig extends JdbcDefaultBatchConfiguration {
}
