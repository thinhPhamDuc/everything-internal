package com.app.internal.config;

import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.listener.logging.SLF4JLogLevel;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

// Task 8 (Observability): wrap HikariDataSource thật bằng datasource-proxy để
// log từng câu SQL (mức DEBUG, tắt mặc định) và luôn cảnh báo (mức WARN) khi
// 1 câu SQL vượt ngưỡng chậm - log này chạy trên cùng thread với
// request/consumer đang xử lý nên tự động mang theo traceId/spanId (Micrometer
// Tracing), giúp nhảy thẳng từ 1 trace chậm trong Tempo sang đúng câu SQL gây
// chậm trong Loki.
@Configuration
public class DataSourceProxyConfig {

    private static final String SLOW_QUERY_LOGGER = "com.app.internal.sql.slow";
    private static final String QUERY_LOGGER = "com.app.internal.sql";

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource actualDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Primary
    @Bean
    public DataSource dataSource(
            HikariDataSource actualDataSource,
            @Value("${app.observability.slow-query-threshold-ms:200}") long slowQueryThresholdMs) {
        return ProxyDataSourceBuilder.create(actualDataSource)
                .name("AirlineDB")
                // Log toàn bộ câu SQL ở DEBUG - im lặng theo mặc định (root
                // level INFO), bật tay khi cần soi kỹ 1 luồng cụ thể.
                .logQueryBySlf4j(SLF4JLogLevel.DEBUG, QUERY_LOGGER)
                // Luôn cảnh báo (WARN) khi 1 câu SQL vượt ngưỡng chậm, bất kể
                // cấu hình log level - đây là tín hiệu không được bỏ lỡ.
                .logSlowQueryBySlf4j(slowQueryThresholdMs, TimeUnit.MILLISECONDS, SLF4JLogLevel.WARN, SLOW_QUERY_LOGGER)
                .build();
    }
}
