package com.app.playground.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bug #4 (CORS & Routing ở API Gateway) - nửa CORS.
 *
 * gateway.cors.allowed-origin mặc định trỏ vào 1 origin KHÔNG tồn tại
 * (http://localhost:9999) - client thật (cors-test-client/index.html, chạy ở
 * origin khác) sẽ bị trình duyệt chặn với lỗi CORS trong Console. Lưu ý: curl
 * sẽ KHÔNG bao giờ thấy lỗi này - CORS là chính sách trình duyệt thực thi,
 * không phải server trả lỗi HTTP.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    public CorsConfig(@Value("${gateway.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*");
    }
}
