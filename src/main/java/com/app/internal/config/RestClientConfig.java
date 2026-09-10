package com.app.internal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// RestClient (đồng bộ, Spring 6.1+) thay vì WebClient - đủ dùng cho 1 lời
// gọi HTTP đơn giản trong @RabbitListener (đã chạy trên thread riêng của
// consumer, không cần non-blocking).
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }
}
