package com.app.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// RestClient (đồng bộ, Spring 6.1+) thay vì WebClient - đủ dùng cho 1 lời
// gọi HTTP đơn giản trong @RabbitListener (đã chạy trên thread riêng của
// consumer, không cần non-blocking).
//
// Task 9: thêm connect/read timeout - lớp phòng thủ Ở TẦNG HTTP dưới
// CompletableFuture.orTimeout() của ThirdPartyFlightFetchService. orTimeout()
// chỉ khiến CompletableFuture BÁO lỗi sớm, KHÔNG hủy được lời gọi HTTP đang
// treo bên dưới (giới hạn đã biết của CompletableFuture) - nếu thiếu timeout
// ở tầng này, thread fetch 1 provider bị treo thật sự (socket không phản hồi)
// sẽ không bao giờ được trả về pool.
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(6_000);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
