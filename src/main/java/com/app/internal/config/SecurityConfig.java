package com.app.internal.config;

import com.app.internal.auth.jwt.JwtFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity // Bật @PreAuthorize trên controller (VD UserController) - thiếu dòng này @PreAuthorize bị lờ đi lặng lẽ, không báo lỗi.
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // Mặc định strength = 10
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/login").permitAll()
                        // /auth/me KHÔNG permitAll -> dùng để test token ở bước 8
                        // Mock API bên thứ 3 (Task 5) - giả lập hệ thống ngoài, không
                        // thuộc phạm vi phân quyền nội bộ.
                        .requestMatchers("/mock/third-party/**").permitAll()
                        // Task 6: search vé - public, client chưa đăng nhập vẫn dùng được
                        // (đúng GIAO_AN Task 6 + TASK6_SEARCH_REDIS_CACHE.md).
                        .requestMatchers("/flights/search").permitAll()
                        // Task 8: health/info/prometheus phải public để Prometheus (chạy
                        // trong container, không có JWT) scrape được, và để dùng làm
                        // health check target (VD ALB) - management.endpoints.web.exposure
                        // đã giới hạn chỉ 3 endpoint này expose ra (application.properties),
                        // không phải "*" nên không lộ endpoint quản trị nhạy cảm.
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Task 9: bug phát hiện lúc test provider-c (mock/third-party) throw
                        // ResponseStatusException - Spring forward nội bộ sang "/error" để
                        // render lỗi (BasicErrorController), dispatch này ĐI QUA LẠI security
                        // filter chain; thiếu dòng permitAll này, MỌI exception ở MỌI endpoint
                        // permitAll (không riêng mock) đều bị trả về 403 thay vì đúng status
                        // code (403 che mất status thật, VD 503/500/400 đều thành 403).
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
