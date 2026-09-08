package com.app.internal.auth.controller;

import com.app.internal.auth.dto.LoginRequest;
import com.app.internal.auth.dto.RegisterRequest;
import com.app.internal.auth.dto.RegisterResponse;
import com.app.internal.auth.dto.TokenResponse;
import com.app.internal.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // Endpoint để test bước 8: gọi API kèm token, xác nhận JwtFilter hoạt động.
    // Route này KHÔNG nằm trong "/auth/**" public list nếu bạn để nguyên
    // matcher "/auth/**" ở SecurityConfig -> cần sửa lại matcher nếu muốn
    // /auth/me bị chặn (xem ghi chú trong SecurityConfig).
    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "userId", authentication.getPrincipal(),
                "authorities", authentication.getAuthorities()
        ));
    }
}
