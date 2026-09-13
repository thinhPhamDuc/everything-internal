package com.app.internal.sync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// Task 9: danh sách nhà cung cấp vé fetch song song (xem
// ThirdPartyFlightFetchService) - record được Spring Boot tự nhận diện
// constructor binding (không cần @ConstructorBinding từ Boot 3.x trở đi vì
// record chỉ có đúng 1 constructor).
@ConfigurationProperties(prefix = "app.sync")
public record SyncProviderProperties(
        List<Provider> providers,
        long providerFetchTimeoutSeconds,
        // Task 9 (mở rộng): retry khi provider "die" (lỗi tức thời - VD
        // 503/connection refused). CỐ Ý KHÔNG retry khi provider "treo"
        // (timeout) - retry ngay 1 cuộc gọi đang hang không giúp gì, chỉ kéo
        // dài thời gian chờ; toàn bộ vòng retry vẫn nằm TRONG
        // providerFetchTimeoutSeconds (ngân sách tổng), nên nếu provider treo
        // ngay từ lần thử đầu, orTimeout() vẫn cắt trước khi kịp retry lần 2 -
        // đúng ý muốn (không lãng phí thời gian retry cái đang treo).
        int providerFetchMaxAttempts,
        long providerRetryDelayMs) {

    public record Provider(String name, String url) {
    }
}
