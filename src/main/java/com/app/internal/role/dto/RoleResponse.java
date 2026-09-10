package com.app.internal.role.dto;

import java.util.List;

public record RoleResponse(Long id, String name, String description, List<String> permissions) {
}
