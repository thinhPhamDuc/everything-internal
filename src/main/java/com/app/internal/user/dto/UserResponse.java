package com.app.internal.user.dto;

import java.time.LocalDateTime;

public record UserResponse(Long id, String email, String fullName, String status, String roleName, LocalDateTime createdAt) {
}

