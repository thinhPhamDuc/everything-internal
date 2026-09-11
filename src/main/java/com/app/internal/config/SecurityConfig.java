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
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
