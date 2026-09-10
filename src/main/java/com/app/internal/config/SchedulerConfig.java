package com.app.internal.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Bật @Scheduled trong toàn app - thiếu annotation này, FlightSyncScheduler
// bị Spring lờ đi lặng lẽ (không lỗi, chỉ đơn giản không bao giờ chạy).
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
